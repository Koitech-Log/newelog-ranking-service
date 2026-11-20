[CmdletBinding()]
param([string]$SourcesRoot,[string]$JavaHome,[switch]$SkipBuild,[switch]$Postgres)
# Execute no PowerShell normal. Downloads oficiais e bancos H2 isolados.
# API JDK: https://github.com/adoptium/api.adoptium.net/blob/main/docs/cookbook.adoc
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
if(-not $SourcesRoot){$SourcesRoot=Join-Path (Split-Path -Parent $repo) 'integration-sources'}
$SourcesRoot=[IO.Path]::GetFullPath($SourcesRoot)
$out=Join-Path $repo ('.local-e2e/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
[IO.Directory]::CreateDirectory($out)|Out-Null
$processes=New-Object Collections.ArrayList
$checks=New-Object Collections.ArrayList
$builds=New-Object Collections.ArrayList
$sourceTestFailures=New-Object Collections.ArrayList
$containers=New-Object Collections.ArrayList
$database='H2 em memoria separado por processo; nao valida PostgreSQL'
$envNames=@('JAVA_HOME','PATH','JWT_SECRET','ADMIN_EMAIL','ADMIN_PASSWORD','ADMIN_NOME','MOTORISTAS_SERVICE_URL','DB_URL','DB_USERNAME','DB_PASSWORD','PORT','FRONTEND_URL','TEST_DB_URL','TEST_DB_DRIVER','TEST_DB_USERNAME','TEST_DB_PASSWORD','POSTGRES_PASSWORD')
$saved=@{};foreach($n in $envNames){$saved[$n]=[Environment]::GetEnvironmentVariable($n,'Process')}
$failure=$null;$http=$null;$started=Get-Date

function Check([bool]$ok,[string]$name){
    if(-not $ok){throw "FALHOU: $name"}
    [void]$checks.Add([pscustomobject]@{name=$name;status='PASS'})
    Write-Host "[OK] $name"
}
function Build([string]$dir,[string]$exe,[string[]]$arguments,[string]$name,[switch]$AllowTestFailures){
    Push-Location -LiteralPath $dir
    try{
        Write-Host "Executando $name..."
        $log=Join-Path $out ($name+'.log')
        $previous=$ErrorActionPreference;$ErrorActionPreference='Continue'
        try{& $exe @arguments *> $log;$code=$LASTEXITCODE}finally{$ErrorActionPreference=$previous}
        [void]$builds.Add([pscustomobject]@{name=$name;exitCode=$code;log=$log})
        if($code -ne 0){
            Get-Content -LiteralPath $log -Tail 18|Write-Host
            # Continua apenas se o Maven confirmou falhas de assercao nos testes.
            # Erros de compilacao, infraestrutura e execucao continuam interrompendo.
            if($AllowTestFailures -and (Select-String -LiteralPath $log -SimpleMatch 'There are test failures.' -Quiet)){
                $failed=0;$errors=0;$details=New-Object Collections.ArrayList
                foreach($xmlFile in @(Get-ChildItem (Join-Path $dir 'target/surefire-reports') -Filter 'TEST-*.xml')){
                    [xml]$xml=Get-Content -LiteralPath $xmlFile.FullName -Raw
                    $failed += [int]$xml.testsuite.failures;$errors += [int]$xml.testsuite.errors
                    foreach($test in @($xml.SelectNodes('//testcase[failure]'))){[void]$details.Add("$($test.classname).$($test.name)")}
                }
                if($failed -gt 0 -and $errors -eq 0){
                    [void]$sourceTestFailures.Add([pscustomobject]@{service=$name;failures=$failed;tests=@($details.ToArray());log=$log})
                    Write-Warning "$name tem $failed falha(s) de assercao. Registradas no relatorio; continuando a integracao com o codigo original."
                    return $false
                }
            }
            throw "Falha em $name. Veja $log"
        }
        if($AllowTestFailures){return $true}
    }finally{Pop-Location}
}
function Java21{
    $candidate=$JavaHome
    if(-not $candidate){$candidate=$saved['JAVA_HOME']}
    if($candidate -and (Test-Path (Join-Path $candidate 'release'))){
        if((Get-Content (Join-Path $candidate 'release') -Raw) -match 'JAVA_VERSION="21[.]'){return $candidate}
        if($JavaHome){throw '-JavaHome precisa ser um JDK 21.'}
    }
    $tools=Join-Path $repo '.local-e2e/tools'
    [IO.Directory]::CreateDirectory($tools)|Out-Null
    foreach($jdk in @(Get-ChildItem -LiteralPath $tools -Directory)){
        if((Test-Path (Join-Path $jdk.FullName 'release')) -and (Test-Path (Join-Path $jdk.FullName 'bin/javac.exe'))){
            if((Get-Content (Join-Path $jdk.FullName 'release') -Raw) -match 'JAVA_VERSION="21[.]'){return $jdk.FullName}
        }
    }
    Write-Host 'Baixando Java 21 portatil do Eclipse Adoptium, sem instalacao global...'
    [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
    $metadata=Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse' -TimeoutSec 60
    $package=@($metadata)[0].binary.package;$uri=[Uri]$package.link
    if($uri.Scheme -ne 'https' -or $uri.Host -ne 'github.com' -or -not $uri.AbsolutePath.StartsWith('/adoptium/temurin21-binaries/releases/download/')){throw 'URL inesperada para o JDK.'}
    if($package.checksum -notmatch '^[a-fA-F0-9]{64}$'){throw 'Checksum JDK ausente.'}
    $zip=Join-Path $tools 'temurin21.zip'
    Invoke-WebRequest -UseBasicParsing -Uri $uri.AbsoluteUri -OutFile $zip -TimeoutSec 600
    if((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash -ne $package.checksum){throw 'SHA-256 do JDK nao confere.'}
    Expand-Archive -LiteralPath $zip -DestinationPath $tools -Force
    foreach($jdk in @(Get-ChildItem -LiteralPath $tools -Directory)){
        if((Test-Path (Join-Path $jdk.FullName 'release')) -and (Test-Path (Join-Path $jdk.FullName 'bin/javac.exe'))){
            if((Get-Content (Join-Path $jdk.FullName 'release') -Raw) -match 'JAVA_VERSION="21[.]'){return $jdk.FullName}
        }
    }
    throw 'JDK 21 nao encontrado apos extracao.'
}
function Launch([string]$name,[string]$dir,[string[]]$arguments){
    $quoted=@($arguments|ForEach-Object{'"'+$_.Replace('"','\"')+'"'}) -join ' '
    $p=Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') -ArgumentList $quoted -WorkingDirectory $dir -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $out "$name.stdout.log") -RedirectStandardError (Join-Path $out "$name.stderr.log")
    [void]$processes.Add([pscustomobject]@{name=$name;process=$p})
    return $p
}
function Source-App([string]$name,[string]$main,[int]$port,[string[]]$extra){
    $dir=Join-Path $SourcesRoot $name
    $deps=(Get-Content -LiteralPath (Join-Path $dir 'e2e-classpath.txt') -Raw).Trim()
    $cp=(Join-Path $dir 'target/classes')+';'+(Join-Path $dir 'target/test-classes')+';'+$deps
    return Launch $name $dir (@('-Dspring.devtools.restart.enabled=false','-cp',$cp,$main,"--server.port=$port",'--server.address=127.0.0.1','--spring.profiles.active=local-e2e')+$extra)
}
function Ready([string]$url,[Diagnostics.Process]$p,[string]$name){
    $deadline=(Get-Date).AddSeconds(120)
    while((Get-Date)-lt $deadline){
        if($p.HasExited){throw "$name encerrou. Veja os logs em $out"}
        try{$r=$http.GetAsync("$url/actuator/health").GetAwaiter().GetResult();try{if([int]$r.StatusCode-eq 200){return}}finally{$r.Dispose()}}catch{}
        Start-Sleep -Milliseconds 500
    }
    throw "Timeout aguardando $name. Veja os logs em $out"
}
function Api([string]$method,[string]$url,[string]$token,$body,[int]$expected=200){
    $req=New-Object Net.Http.HttpRequestMessage((New-Object Net.Http.HttpMethod($method)),$url)
    if($token){$req.Headers.Authorization=New-Object Net.Http.Headers.AuthenticationHeaderValue('Bearer',$token)}
    if($null-ne $body){$json=ConvertTo-Json -InputObject $body -Depth 10 -Compress;$req.Content=New-Object Net.Http.StringContent($json,[Text.Encoding]::UTF8,'application/json')}
    try{
        $r=$http.SendAsync($req).GetAwaiter().GetResult()
        try{
            $text=$r.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if([int]$r.StatusCode-ne $expected){throw "$method $url retornou $([int]$r.StatusCode), esperado $expected. $text"}
            if($text -and $r.IsSuccessStatusCode){return ConvertFrom-Json -InputObject $text}
        }finally{$r.Dispose()}
    }finally{$req.Dispose()}
}
function Upload([string]$url,[string]$token,[string]$name,[string]$text){
    $req=New-Object Net.Http.HttpRequestMessage([Net.Http.HttpMethod]::Post,$url)
    $req.Headers.Authorization=New-Object Net.Http.Headers.AuthenticationHeaderValue('Bearer',$token)
    $form=New-Object Net.Http.MultipartFormDataContent
    $bytes=[Text.Encoding]::GetEncoding('iso-8859-1').GetBytes($text)
    $file=New-Object Net.Http.ByteArrayContent(,$bytes)
    $file.Headers.ContentType=New-Object Net.Http.Headers.MediaTypeHeaderValue('text/csv')
    $form.Add($file,'file',$name);$req.Content=$form
    try{
        $r=$http.SendAsync($req).GetAwaiter().GetResult()
        try{
            $result=$r.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if(-not $r.IsSuccessStatusCode){throw "Upload retornou $([int]$r.StatusCode). $result"}
            return ConvertFrom-Json -InputObject $result
        }finally{$r.Dispose()}
    }finally{$req.Dispose()}
}
function Row([string]$id,[string]$date,[string]$name,[string]$cpf,[string]$plate,[string]$frete,[string]$pedagio){
    $line=New-Object string[] 60;for($i=0;$i-lt 60;$i++){$line[$i]=''}
    $line[0]=$id;$line[2]=$date;$line[3]=$name;$line[4]=$cpf;$line[15]='Agregado E2E';$line[16]=$cpf
    $line[19]=$plate;$line[38]=$frete;$line[40]=$pedagio;$line[51]='0';$line[56]='0';$line[59]='DISPONIVEL'
    return $line-join ';'
}
function Docker-Ready {
    $previous=$ErrorActionPreference;$ErrorActionPreference='Continue'
    try { & docker info --format '{{.ServerVersion}}' *> (Join-Path $out 'docker-info.log'); return ($LASTEXITCODE-eq 0) }
    finally { $ErrorActionPreference=$previous }
}
function Start-Docker {
    $docker=(Get-Command docker.exe -ErrorAction Stop).Source
    if(Docker-Ready){return}
    $desktop=[IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $docker) '../../Docker Desktop.exe'))
    if(-not(Test-Path -LiteralPath $desktop)){throw 'Abra o Docker Desktop e execute o teste novamente.'}
    Write-Host 'Iniciando Docker Desktop para bancos PostgreSQL temporarios...'
    Start-Process -FilePath $desktop -WindowStyle Hidden|Out-Null
    $deadline=(Get-Date).AddSeconds(180)
    while((Get-Date)-lt $deadline){if(Docker-Ready){return};Start-Sleep -Seconds 2}
    throw 'Docker nao ficou pronto. Confira Docker Desktop/WSL e execute novamente.'
}
function New-Postgres([string]$role,[int]$port){
    $name='ranking-e2e-'+$role+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
    $previous=$ErrorActionPreference;$ErrorActionPreference='Continue'
    try{
        & docker run --detach --name $name -p "127.0.0.1:${port}:5432" -e POSTGRES_PASSWORD -e POSTGRES_USER=e2e -e POSTGRES_DB=e2e postgres:16-alpine *> (Join-Path $out "$role-postgres.log")
        $code=$LASTEXITCODE
    }finally{$ErrorActionPreference=$previous}
    if($code-ne 0){throw "Nao foi possivel criar PostgreSQL $role. Veja os logs."}
    [void]$containers.Add($name)
    $deadline=(Get-Date).AddSeconds(90)
    while((Get-Date)-lt $deadline){
        $previous=$ErrorActionPreference;$ErrorActionPreference='Continue'
        try{& docker exec $name pg_isready -U e2e -d e2e *> (Join-Path $out "$role-postgres-ready.log");$ready=($LASTEXITCODE-eq 0)}
        finally{$ErrorActionPreference=$previous}
        if($ready){return "jdbc:postgresql://127.0.0.1:$port/e2e"}
        Start-Sleep -Milliseconds 500
    }
    throw "PostgreSQL $role nao ficou pronto."
}
try{
    foreach($name in @('newelog-auth-service','newelog-motoristas-service','newelog-manifesto-service','newelog-frota-web')){
        if(-not(Test-Path -LiteralPath (Join-Path $SourcesRoot $name))){throw "Copia ausente: $SourcesRoot/$name"}
    }
    $ports=@(18080,18081,18082,18083)
    if($Postgres){$ports+=@(18532,18533)}
    foreach($port in $ports){
        $listener=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$port)
        try{$listener.Start()}catch{throw "Porta $port indisponivel ou bloqueada: $($_.Exception.Message). Nenhum processo existente sera encerrado."}finally{$listener.Stop()}
    }
    Add-Type -AssemblyName System.Net.Http
    $http=New-Object Net.Http.HttpClient;$http.Timeout=[TimeSpan]::FromSeconds(20)
    $env:JAVA_HOME=Java21;$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$saved['PATH']
    $random=New-Object byte[] 48;$rng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try{$rng.GetBytes($random)}finally{$rng.Dispose()}
    $env:JWT_SECRET=[Convert]::ToBase64String($random)
    $password='Teste!A1'+[Guid]::NewGuid().ToString('N')
    # Os testes do auth criam os proprios usuarios e exigem banco sem bootstrap.
    $env:ADMIN_EMAIL='';$env:ADMIN_PASSWORD='';$env:ADMIN_NOME=''
    $env:DB_URL='jdbc:h2:mem:e2e_unused';$env:DB_USERNAME='sa';$env:DB_PASSWORD='';$env:PORT=''
    $env:FRONTEND_URL='http://localhost:5173';$env:MOTORISTAS_SERVICE_URL='http://127.0.0.1:18080'
    foreach($name in @('TEST_DB_URL','TEST_DB_DRIVER','TEST_DB_USERNAME','TEST_DB_PASSWORD')){[Environment]::SetEnvironmentVariable($name,$null,'Process')}
    if(-not $SkipBuild){
        foreach($name in @('newelog-auth-service','newelog-motoristas-service','newelog-manifesto-service')){
            $sourceDir=Join-Path $SourcesRoot $name
            $passed=Build $sourceDir '.\mvnw.cmd' @('-B','-ntp','clean','verify','dependency:build-classpath','-DincludeScope=test','-Dmdep.outputFile=e2e-classpath.txt') $name -AllowTestFailures
            if(-not $passed){
                Build $sourceDir '.\mvnw.cmd' @('-B','-ntp','-DskipTests','package','dependency:build-classpath','-DincludeScope=test','-Dmdep.outputFile=e2e-classpath.txt') ($name+'-package-after-test-failure')
            }
        }
        Build $repo '.\mvnw.cmd' @('-B','-ntp','clean','verify') 'ranking'
        $npm=(Get-Command npm.cmd -ErrorAction Stop).Source
        Build (Join-Path $SourcesRoot 'newelog-frota-web') $npm @('ci') 'front-install'
        Build (Join-Path $SourcesRoot 'newelog-frota-web') $npm @('run','build') 'front-build'
        Build (Join-Path $SourcesRoot 'newelog-frota-web') $npm @('run','lint') 'front-lint'
    }
    $env:ADMIN_EMAIL='gestor-e2e@newelog.invalid';$env:ADMIN_PASSWORD=$password;$env:ADMIN_NOME='Gestor E2E'
    $auth='http://127.0.0.1:18082';$mot='http://127.0.0.1:18080';$man='http://127.0.0.1:18081';$rank='http://127.0.0.1:18083'
    $db=@('--spring.datasource.driver-class-name=org.h2.Driver','--spring.datasource.username=sa','--spring.datasource.password=','--spring.jpa.hibernate.ddl-auto=validate')
    $authDb=@('--spring.datasource.url=jdbc:h2:mem:e2e_auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1')
    $motDb=@('--spring.datasource.url=jdbc:h2:mem:e2e_motoristas;MODE=PostgreSQL;DB_CLOSE_DELAY=-1','--spring.flyway.target=1')
    if($Postgres){
        Start-Docker
        $env:POSTGRES_PASSWORD='Db!A1'+[Guid]::NewGuid().ToString('N')
        $env:DB_PASSWORD=$env:POSTGRES_PASSWORD
        $authUrl=New-Postgres 'auth' 18533
        $motUrl=New-Postgres 'motoristas' 18532
        $db=@('--spring.datasource.driver-class-name=org.postgresql.Driver','--spring.datasource.username=e2e','--spring.jpa.hibernate.ddl-auto=validate')
        $authDb=@("--spring.datasource.url=$authUrl");$motDb=@("--spring.datasource.url=$motUrl")
        $database='PostgreSQL 16 real em dois containers temporarios, com migrations e seed originais'
    }
    $pa=Source-App 'newelog-auth-service' 'br.com.newelog.auth.AuthServiceApplication' 18082 ($db+$authDb)
    Ready $auth $pa 'auth'
    $pm=Source-App 'newelog-motoristas-service' 'br.com.newelog.motoristas.MotoristasServiceApplication' 18080 ($db+$motDb)
    Ready $mot $pm 'motoristas'
    $pi=Source-App 'newelog-manifesto-service' 'br.com.newelog.NewelogServiceTemplateApplication' 18081 @()
    Ready $man $pi 'manifesto'
    $pr=Launch 'ranking' $repo @('-jar',(Join-Path $repo 'target/newelog-ranking-service-0.0.1-SNAPSHOT.jar'),'--server.port=18083','--server.address=127.0.0.1')
    Ready $rank $pr 'ranking'
    Check $true 'Quatro servicos reais inicializados em portas isoladas'
    $login=Api 'POST' "$auth/api/auth/login" '' @{email=$env:ADMIN_EMAIL;senha=$password}
    Check ([bool]$login.token) 'Login real do gestor e emissao do JWT';$gestor=$login.token
    $opPassword='Teste!B2'+[Guid]::NewGuid().ToString('N')
    $op=Api 'POST' "$auth/api/usuarios" $gestor @{nome='Operador E2E';email='operador-e2e@newelog.invalid';senha=$opPassword;perfil='operador'} 201
    $opLogin=Api 'POST' "$auth/api/auth/login" '' @{email='operador-e2e@newelog.invalid';senha=$opPassword}
    Check ([bool]$opLogin.token) 'Cadastro e login reais do operador'
    Api 'GET' "$rank/ranking?ano=2090&mes=8" '' $null 401|Out-Null;Check $true 'Sem token retorna 401'
    Api 'GET' "$rank/ranking?ano=2090&mes=8" $opLogin.token $null 403|Out-Null;Check $true 'Token real de operador retorna 403'
    Api 'GET' "$rank/ranking?ano=2090&mes=13" $gestor $null 400|Out-Null;Check $true 'Mes invalido retorna 400'
    $empty=Api 'GET' "$rank/ranking?ano=2090&mes=8" $gestor $null
    Check (@($empty.dados).Count-eq 0) 'Banco isolado vazio retorna ranking vazio'
    $csv=@(
        ((0..59|ForEach-Object{"col$_"})-join ';')
        (Row 'e2e-a1' '01/08/2090' 'E2E Ana' '52998224725' 'ABC1D23' '1000,00' '100,00')
        (Row 'e2e-a2' '10/08/2090' 'E2E Ana' '52998224725' 'ABC1D23' '400,00' '40,00')
        (Row 'e2e-b1' '20/08/2090' 'E2E Bruno' '11144477735' 'DEF2E34' '800,00' '50,00')
        (Row 'e2e-a0' '31/07/2090' 'E2E Ana' '52998224725' 'ABC1D23' '9999,00' '0,00')
    )-join [Environment]::NewLine
    $upload=Upload "$man/manifestos/upload" $gestor 'manifesto-e2e.csv' $csv
    Check ($upload.totalRejeitados-eq 0 -and $upload.totalProcessados-eq 4) 'CSV passa pelo manifesto e persiste no motoristas'
    $r=Api 'GET' "$rank/ranking?ano=2090&mes=8" $gestor $null
    Check (@($r.dados).Count-eq 2) 'Ranking encontra dois motoristas importados'
    Check ($r.dados[0].motorista-eq 'E2E Ana' -and [decimal]$r.dados[0].resultado-eq 1260 -and $r.dados[0].quantidadeViagens-eq 2 -and $r.dados[0].posicao-eq 1) 'Valores, posicao e viagens de Ana conferem'
    Check ($r.dados[1].motorista-eq 'E2E Bruno' -and [decimal]$r.dados[1].resultado-eq 750 -and $r.dados[1].posicao-eq 2) 'Valores e posicao de Bruno conferem'
    $dash=Api 'GET' "$mot/api/dashboard/rentabilidade-media?ano=2090&mes=8" $gestor $null
    Check ([decimal]$dash.geral.rentabilidadeTotal-eq 2010 -and $dash.geral.quantidadeViagens-eq 3) 'Dashboard real confere com os totais esperados'
    foreach($q in @('E2E%20Bruno','11144477735','111.444.777-35','DEF-2E34')){
        $filtered=Api 'GET' "$rank/ranking?ano=2090&mes=8&busca=$q" $gestor $null
        Check (@($filtered.dados).Count-eq 1 -and $filtered.dados[0].posicao-eq 2) "Busca $q preserva a posicao"
    }
    $v=Api 'GET' "$rank/api/ranking?ano=2090&mes=8&tipo=veiculos" $gestor $null
    Check (@($v.dados).Count-eq 2 -and $v.dados[0].veiculo-eq 'ABC1D23' -and [decimal]$v.dados[0].resultado-eq 1260) 'Alias e agrupamento por veiculo conferem'
    $july=Api 'GET' "$rank/ranking?ano=2090&mes=7" $gestor $null
    Check (@($july.dados).Count-eq 1 -and [decimal]$july.dados[0].resultado-eq 9999) 'Troca de mes usa somente viagens do periodo'
    $missing=Api 'GET' "$rank/ranking?ano=2090&mes=1" $gestor $null
    Check (@($missing.dados).Count-eq 0) 'Mes sem viagens retorna lista vazia'
    $againUpload=Upload "$man/manifestos/upload" $opLogin.token 'manifesto-e2e.csv' $csv
    $again=Api 'GET' "$rank/ranking?ano=2090&mes=8" $gestor $null
    Check ($againUpload.totalRejeitados-eq 0 -and @($again.dados).Count-eq 2 -and [decimal]$again.dados[0].resultado-eq 1260 -and $again.dados[0].quantidadeViagens-eq 2) 'Reimportacao por operador nao duplica viagens nem altera pedagio'
    $vehiclesCsv=@('Placa;Marca;Modelo;Cor;Tipo Veiculo;Agregado','ABC1D23;MarcaTeste;ModeloTeste;BRANCA;FIORINO;Agregado E2E','DEF2E34;MarcaTeste;ModeloTeste;BRANCA;FIORINO;Agregado E2E')-join [Environment]::NewLine
    $vu=Upload "$man/veiculos/upload" $gestor 'veiculos-e2e.csv' $vehiclesCsv
    Check ($vu.rejeitados-eq 0 -and ($vu.cadastrados+$vu.atualizados)-eq 2) 'Importacao real do CSV de veiculos'
    $after=Api 'GET' "$rank/ranking?ano=2090&mes=8&tipo=veiculos" $gestor $null
    Check (@($after.dados).Count-eq 2 -and [decimal]$after.dados[0].resultado-eq 1260) 'Cadastro de veiculos preserva o ranking'
    $pm.Kill();$pm.WaitForExit()
    Api 'GET' "$rank/ranking?ano=2090&mes=8" $gestor $null 503|Out-Null;Check $true 'Queda real da fonte retorna 503 sem resultado parcial'
    if($sourceTestFailures.Count -gt 0){$failure='Integracao HTTP concluida, mas ha falhas nos testes dos servicos de origem. Consulte sourceTestFailures no relatorio.';Write-Warning $failure}
}catch{$failure=$_.Exception.Message;Write-Host "FALHOU: $failure" -ForegroundColor Red}
finally{
    foreach($entry in $processes){
        try{if(-not $entry.process.HasExited){$entry.process.Kill();$entry.process.WaitForExit()}}
        catch{Write-Warning "Confira o processo iniciado para $($entry.name): $($_.Exception.Message)"}
        finally{$entry.process.Dispose()}
    }
    if($http){$http.Dispose()}
    foreach($container in $containers){
        $previous=$ErrorActionPreference;$ErrorActionPreference='Continue'
        try{& docker rm --force --volumes $container *> (Join-Path $out "$container-cleanup.log");if($LASTEXITCODE-ne 0){Write-Warning "Confira container temporario $container"}}
        finally{$ErrorActionPreference=$previous}
    }
    foreach($name in $envNames){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    $report=[pscustomobject]@{
        status=$(if($failure){'FAIL'}else{'PASS'})
        started=$started.ToString('o');finished=(Get-Date).ToString('o')
        database=$database
        scope='Backend HTTP real; front por build/lint, sem teste de navegador'
        sourceRoot=$SourcesRoot
        sourceRevisions=$(if(Test-Path (Join-Path $SourcesRoot 'revisions.json')){Get-Content (Join-Path $SourcesRoot 'revisions.json') -Raw | ConvertFrom-Json}else{$null})
        checks=@($checks.ToArray());builds=@($builds.ToArray());sourceTestFailures=@($sourceTestFailures.ToArray());failure=$failure
    }
    $report|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $out 'resultado.json') -Encoding UTF8
    Write-Host "Relatorio: $(Join-Path $out 'resultado.json')"
}
if($failure){exit 1}
Write-Host "PASS: $($checks.Count) verificacoes HTTP reais; processos encerrados." -ForegroundColor Green
