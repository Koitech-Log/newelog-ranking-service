package br.com.newelog.ranking.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LimitacaoViagensProperties.class)
public class LimitacaoViagensConfig {}