/**
 * JBoss, Home of Professional Open Source
 * Copyright 2016, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redhat.developers.msa.hola;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;

import org.apache.deltaspike.core.api.config.ConfigResolver;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.keycloak.KeycloakPrincipal;
import org.keycloak.KeycloakSecurityContext;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.ServerSpan;
import com.github.kristofa.brave.http.DefaultSpanNameProvider;
import com.github.kristofa.brave.httpclient.BraveHttpRequestInterceptor;
import com.github.kristofa.brave.httpclient.BraveHttpResponseInterceptor;

import feign.Logger;
import feign.Logger.Level;
import feign.httpclient.ApacheHttpClient;
import feign.hystrix.HystrixFeign;
import feign.jackson.JacksonDecoder;
import io.swagger.annotations.ApiOperation;

@Path("/")
public class HolaResource {

    @Inject
    private Brave brave;

    @Context
    private SecurityContext securityContext;

    @Context
    private HttpServletRequest servletRequest;

    @GET
    @Path("/hola")
    @Produces("text/plain")
    @ApiOperation("Returns the greeting in Spanish")
    public String hola() {
        String hostname = System.getenv().getOrDefault("HOSTNAME", "unknown");
        String translation = ConfigResolver
            .resolve("hello")
            .withDefault("Hola de %s")
            .logChanges(true)
            // 5 Seconds cache only for demo purpose
            .cacheFor(TimeUnit.SECONDS, 5)
            .getValue();
        return String.format(translation, hostname);

    }

    @GET
    @Path("/hola-chaining")
    @Produces("application/json")
    @ApiOperation("Returns the greeting plus the next service in the chain")
    public List<String> holaChaining() {
        List<String> greetings = new ArrayList<>();
        greetings.add(hola());
        greetings.addAll(getNextService().aloha());
        return greetings;
    }

    @GET
    @Path("/hola-secured")
    @Produces("text/plain")
    @ApiOperation("Returns a message that is only available for authenticated users")
    public String holaSecured() {
        // this will set the user id as userName
        String userName = securityContext.getUserPrincipal().getName();

        if (securityContext.getUserPrincipal() instanceof KeycloakPrincipal) {
            @SuppressWarnings("unchecked")
            KeycloakPrincipal<KeycloakSecurityContext> kp = (KeycloakPrincipal<KeycloakSecurityContext>) securityContext.getUserPrincipal();

            // this is how to get the real userName (or rather the login name)
            userName = kp.getKeycloakSecurityContext().getToken().getName();
        }
        return "This is a Secured resource. You are loged as " + userName;

    }

    @GET
    @Path("/logout")
    @Produces("text/plain")
    @ApiOperation("Logout")
    public String logout() throws ServletException {
        servletRequest.logout();
        return "Logged out";
    }

    @GET
    @Path("/health")
    @Produces("text/plain")
    @ApiOperation("Used to verify the health of the service")
    public String health() {
        return "I'm ok";
    }

    /**
     * This is were the "magic" happens: it creates a Feign, which is a proxy interface for remote calling a REST endpoint with
     * Hystrix fallback support.
     *
     * @return The feign pointing to the service URL and with Hystrix fallback.
     */
    private AlohaService getNextService() {
        final String serviceName = "aloha";
        // This stores the Original/Parent ServerSpan from ZiPkin.
        final ServerSpan serverSpan = brave.serverSpanThreadBinder().getCurrentServerSpan();
        final CloseableHttpClient httpclient =
            HttpClients.custom()
                .addInterceptorFirst(new BraveHttpRequestInterceptor(brave.clientRequestInterceptor(), new DefaultSpanNameProvider()))
                .addInterceptorFirst(new BraveHttpResponseInterceptor(brave.clientResponseInterceptor()))
                .build();
        String url = String.format("http://%s:8080/", serviceName);
        return HystrixFeign.builder()
            // Use apache HttpClient which contains the ZipKin Interceptors
            .client(new ApacheHttpClient(httpclient))
            // Bind Zipkin Server Span to Feign Thread
            .requestInterceptor((t) -> brave.serverSpanThreadBinder().setCurrentSpan(serverSpan))
            .logger(new Logger.ErrorLogger()).logLevel(Level.BASIC)
            .decoder(new JacksonDecoder())
            .target(AlohaService.class, url,
                () -> Collections.singletonList("Aloha response (fallback)"));
    }

}
