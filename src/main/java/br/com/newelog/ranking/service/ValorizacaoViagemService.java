package br.com.newelog.ranking.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import br.com.newelog.ranking.config.ValorizacaoProperties;

@Service
public class ValorizacaoViagemService {
    private final ValorizacaoProperties props;

    public ValorizacaoViagemService(ValorizacaoProperties props) {
        this.props = props;
    }

    /** Devolve o valor valorizado se o destino for São Paulo; senão, o valor base. */
    public BigDecimal aplicar(BigDecimal valorBase, String destino) {
        Objects.requireNonNull(valorBase, "valorBase é obrigatório");
        if (ehSaoPaulo(destino)) {
            return valorBase.multiply(props.fatorSaoPaulo()).setScale(2, RoundingMode.HALF_UP);
        }
        return valorBase; // fora de qualquer regra = valor base
    }

    private boolean ehSaoPaulo(String destino) {
        if (destino == null) return false;
        String normalizado = Normalizer.normalize(destino, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")   // tira os acentos: "São" vira "Sao"
                .trim()
                .toLowerCase(Locale.ROOT);
        return normalizado.equals("sao paulo");
    }
}