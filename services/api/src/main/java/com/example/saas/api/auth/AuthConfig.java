package com.example.saas.api.auth;

import com.example.saas.api.auth.domain.PasswordHashProperties;
import com.example.saas.api.auth.email.AppProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ AppProperties.class, PasswordHashProperties.class })
class AuthConfig {

}
