package br.com.newelog.ranking.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper) throws Exception {
        var entryPointPadrao = new BearerTokenAuthenticationEntryPoint();
        var negadoPadrao = new BearerTokenAccessDeniedHandler();
        AuthenticationEntryPoint naoAutenticado = (request, response, e) -> {
            entryPointPadrao.commence(request, response, e);
            responder(response, mapper, "Sessão expirada ou token inválido.");
        };
        AccessDeniedHandler semPermissao = (request, response, e) -> {
            negadoPadrao.handle(request, response, e);
            responder(response, mapper, "Você não tem permissão para acessar o ranking.");
        };
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("perfil");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return http.csrf(c -> c.disable()).cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .anyRequest().hasRole("GESTOR"))
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint(naoAutenticado).accessDeniedHandler(semPermissao))
            .exceptionHandling(e -> e.authenticationEntryPoint(naoAutenticado).accessDeniedHandler(semPermissao))
            .build();
    }

    // Mesmo corpo {timestamp, mensagem} do ApiExceptionHandler; o status e o WWW-Authenticate padrão são mantidos.
    private static void responder(HttpServletResponse response, ObjectMapper mapper, String mensagem) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        var corpo = new LinkedHashMap<String, String>();
        corpo.put("timestamp", Instant.now().toString());
        corpo.put("mensagem", mensagem);
        mapper.writeValue(response.getWriter(), corpo);
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("JWT_SECRET deve ter pelo menos 32 bytes.");
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(bytes, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("newelog-auth-service"));
        return decoder;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.frontend-url}") String frontend) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(frontend));
        config.setAllowedMethods(List.of("GET", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
