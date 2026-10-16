package chatting;

import chatting.service.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationSuccessHandler loginSuccessHandler) throws Exception {
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        http
            .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/error", "/actuator/health/**", "/login/**", "/oauth2/**", "/api/csrf", "/api/me").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> response.sendError(401)))
            .oauth2Login(oauth -> oauth.successHandler(loginSuccessHandler))
            .logout(logout -> logout.logoutSuccessUrl("/").invalidateHttpSession(true).deleteCookies("JSESSIONID", "SESSION"));
        return http.build();
    }

    @Bean
    AuthenticationSuccessHandler loginSuccessHandler(UserService users) {
        return (request, response, authentication) -> {
            OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
            OAuth2User principal = token.getPrincipal();
            String provider = token.getAuthorizedClientRegistrationId();
            String subject = principal.getName();
            Object display = principal.getAttributes().get("name");
            if (provider.equals("kakao")) {
                Object account = principal.getAttributes().get("kakao_account");
                if (account instanceof java.util.Map<?, ?> accountMap) {
                    Object profile = accountMap.get("profile");
                    if (profile instanceof java.util.Map<?, ?> profileMap) display = profileMap.get("nickname");
                }
            }
            String name = String.valueOf(display == null ? provider + " 사용자" : display);
            name = name.strip();
            if (name.isBlank()) name = provider + " 사용자";
            if (name.length() > 80) name = name.substring(0, 80);
            var userId = users.getOrCreateOAuthUser(provider, subject, name);
            request.getSession(true).setAttribute("appUserId", userId.toString());
            response.sendRedirect("/");
        };
    }
}
