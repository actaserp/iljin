package mes.config;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
//import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import mes.domain.security.AjaxAwareLoginUrlAuthenticationEntryPoint;

import mes.domain.security.CustomAccessDeniedHandler;
import mes.domain.security.CustomAuthenticationFailureHandler;
import mes.domain.security.CustomAuthenticationManager;
import mes.domain.security.CustomAuthenticationSuccessHandler;
import org.springframework.beans.factory.annotation.Value;


@Configuration
@ComponentScan("mes.domain.security")
public class SecurityConfiguration {

    @Autowired
    private CustomAuthenticationSuccessHandler customAuthenticationSuccessHandler;

    @Autowired
    private CustomAuthenticationFailureHandler customAuthenticationFailureHandler;

    @Value("${server.servlet.session.cookie.name}")
    private String sessionCookieName;

    @Bean(name="authenticationManager")
    CustomAuthenticationManager authenticationManager() {
        return new CustomAuthenticationManager();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        // 1. 헤더 설정
        http
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                );

        // 2. CSRF 예외 경로 (기존 pda 및 기타 추가)
        http
                .csrf(csrf -> csrf
                        .ignoringAntMatchers(
                                "/api/files/upload/**",
                                "/popbill/webhook",
                                // 앱에는 CSRF 토큰을 심을 곳이 없다(세션 쿠키만 보낸다).
                                // 그래서 앱이 쓰는 API 는 /pda/** 아래 래퍼 컨트롤러로 감싼다 —
                                // /api/** 를 여기에 하나씩 추가하면 웹 화면의 CSRF 보호까지 함께 풀린다.
                                "/pda/**"
                        )
                );

        // 3. 권한 설정 (기존 소스의 모든 permitAll 경로 통합)
        http
                .authorizeRequests(auth -> auth
                        .antMatchers(
                                // 기본 페이지 및 인증
                                "/login", "/logout", "/postLogin", "/intro", "/error", "/alive",
                                // 정적 리소스
                                "/resource/**", "/img/**", "/images/**", "/js/**", "/css/**",
                                "/assets_mobile/**", "/font/**", "/robots.txt", "/favicon.ico",
                                // PDA 관련
                                "/pda/login", "/pda/app/version/**", "/pda/**",
                                // API 및 외부 연동
                                "/useridchk/**", "/user-auth/**", "/user-auth/save",
                                "/popbill/webhook", "/api/transaction/input/**",
                                "/api/das_device", "/authentication/**"
                        ).permitAll()
                        .antMatchers("/setup").hasAuthority("admin")
                        .anyRequest().authenticated()
                );

        // 4. 로그인 설정
        http
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/postLogin")
                        .successHandler(customAuthenticationSuccessHandler)
                        .failureHandler(customAuthenticationFailureHandler)
                        .permitAll()
                );

        // 5. 로그아웃 설정
        http
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login")
                        .invalidateHttpSession(true)
                        .deleteCookies(sessionCookieName)
                        .clearAuthentication(true)
                        .permitAll()
                );

        // 6. 예외 처리 및 기타 (기존 소스의 AjaxAware 및 AccessDenied 적용)
        http.httpBasic(httpBasic -> httpBasic.disable());

        http.exceptionHandling(exception -> exception
                .accessDeniedHandler(new CustomAccessDeniedHandler())
                .authenticationEntryPoint(new AjaxAwareLoginUrlAuthenticationEntryPoint("/login"))
        );

        return http.build();
    }
}

