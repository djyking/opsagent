package com.opsagent.common.security;
import java.time.Instant;
public record IssuedToken(String token,String tokenId,Instant expiresAt){}
