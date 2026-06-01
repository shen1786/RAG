package com.example.demo.Config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.example.demo.service.AuthSeedService;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    private final AuthSeedService authSeedService;

    public SaTokenConfig(AuthSeedService authSeedService) {
        this.authSeedService = authSeedService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()) {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Object handler) throws Exception {
                if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) {
                    return true;
                }
                return super.preHandle(request, response, handler);
            }
        })
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/login",
                        "/auth/register",
                        "/actuator/**",
                        "/error",
                        "/",
                        "/index.html",
                        "/static/**",
                        "/css/**",
                        "/js/**",
                        "/favicon.ico"
                );
    }

    @PostConstruct
    public void seedDefaultRoles() {
        authSeedService.seedRolesAndPermissions();
    }
}
