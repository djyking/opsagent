package com.opsagent.common.security;
import java.security.Principal;import java.util.List;
public record OpsPrincipal(long userId,String username,String tokenId,List<String> roles) implements Principal {public OpsPrincipal{roles=List.copyOf(roles);}@Override public String getName(){return username;}}
