package com.opsagent.common.security;
import java.time.Duration;import java.util.ArrayList;import java.util.List;import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("ops.security") public class JwtProperties {private String secret="";private Duration accessTokenTtl=Duration.ofMinutes(30);private List<String> permitAll=new ArrayList<>(List.of("/actuator/health","/actuator/info"));
 public String getSecret(){return secret;}public void setSecret(String v){secret=v;}public Duration getAccessTokenTtl(){return accessTokenTtl;}public void setAccessTokenTtl(Duration v){accessTokenTtl=v;}public List<String> getPermitAll(){return permitAll;}public void setPermitAll(List<String> v){permitAll=v;}}
