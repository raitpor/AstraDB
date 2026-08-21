package com.astradb.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全配置：astradb.security.enabled 开关（默认 false）。
 * 开启后 /api/** 与管理页面需认证（Basic + 表单登录），/api/health 与静态资源放行。
 * API 为无状态 POST + Basic 认证，故禁用 CSRF（仅启用表单登录会话场景无副作用）。
 */
@Configuration
public class SecurityConfig {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                  @Value("${astradb.security.enabled:false}") boolean enabled)
            throws Exception {
        http.csrf(c -> c.disable());
        if (!enabled) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/css/**", "/js/**", "/api/health").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(
            @Value("${astradb.security.username:admin}") String username,
            @Value("${astradb.security.password:admin123}") String password,
            PasswordEncoder encoder) {
        // SS-3：不再使用 {noop} 明文口令；配置值若已带编码器前缀（如 {bcrypt}$2a$...）则原样使用，
        // 否则按明文经 DelegatingPasswordEncoder（默认 BCrypt）编码后存储。
        String stored = password.startsWith("{") ? password : encoder.encode(password);
        if ("admin123".equals(password)) {
            // 默认弱口令：仅告警不阻断（本地开发默认），生产须经 ASTRA_DB_SECURITY_PASSWORD 覆盖
            log.warn("检测到默认口令 admin123，开启鉴权后请立即通过环境变量 ASTRA_DB_SECURITY_PASSWORD 修改");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password(stored).roles("ADMIN").build());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return org.springframework.security.crypto.factory.PasswordEncoderFactories
                .createDelegatingPasswordEncoder();
    }
}
