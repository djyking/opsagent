package com.opsagent.gateway;
import java.util.List;import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("ops.gateway.security") public class GatewaySecurityProperties {private String jwtSecret="";private List<String> publicPaths=List.of("/api/auth/login","/api/auth/refresh","/actuator/health");public String getJwtSecret(){return jwtSecret;}public void setJwtSecret(String v){jwtSecret=v;}public List<String> getPublicPaths(){return publicPaths;}public void setPublicPaths(List<String> v){publicPaths=v;}}
