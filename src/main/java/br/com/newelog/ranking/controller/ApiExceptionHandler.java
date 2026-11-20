package br.com.newelog.ranking.controller;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    record Erro(Instant timestamp, String mensagem) {}
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Erro> tratar(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(new Erro(Instant.now(), e.getReason()));
    }
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Erro> parametro(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new Erro(Instant.now(), "Parâmetros obrigatórios: ano e mes numéricos."));
    }
}
