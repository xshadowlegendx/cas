package org.apereo.cas.webauthn.web.flow;

import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.web.flow.actions.AbstractMultifactorAuthenticationAction;
import org.apereo.cas.web.support.WebUtils;
import org.apereo.cas.webauthn.WebAuthnMultifactorAuthenticationProvider;

import com.yubico.core.RegistrationStorage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedGrantedAuthoritiesWebAuthenticationDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.webflow.execution.Event;
import org.springframework.webflow.execution.RequestContext;

import javax.servlet.http.HttpServletResponse;
import java.util.stream.Collectors;

/**
 * This is {@link WebAuthnStartRegistrationAction}.
 *
 * @author Misagh Moayyed
 * @since 6.1.0
 */
@RequiredArgsConstructor
@Getter
@Slf4j
public class WebAuthnStartRegistrationAction extends AbstractMultifactorAuthenticationAction<WebAuthnMultifactorAuthenticationProvider> {

    /**
     * Attribute name that points to the web application id put into the webflow.
     */
    public static final String FLOW_SCOPE_WEB_AUTHN_APPLICATION_ID = "webauthnApplicationId";

    private final RegistrationStorage webAuthnCredentialRepository;

    private final CasConfigurationProperties casProperties;

    private final CsrfTokenRepository csrfTokenRepository;

    @Override
    protected Event doExecute(final RequestContext requestContext) {
        val webAuthn = casProperties.getAuthn().getMfa().getWebAuthn().getCore();
        val authn = WebUtils.getAuthentication(requestContext);
        val principal = resolvePrincipal(authn.getPrincipal());
        val attributes = principal.getAttributes();

        val flowScope = requestContext.getFlowScope();
        if (attributes.containsKey(webAuthn.getDisplayNameAttribute())) {
            flowScope.put("displayName",
                CollectionUtils.firstElement(attributes.get(webAuthn.getDisplayNameAttribute())));
        } else {
            flowScope.put("displayName", principal.getId());
        }
        flowScope.put(FLOW_SCOPE_WEB_AUTHN_APPLICATION_ID, webAuthn.getApplicationId());

        val request = WebUtils.getHttpServletRequestFromExternalWebflowContext(requestContext);
        val response = WebUtils.getHttpServletResponseFromExternalWebflowContext(requestContext);
        request.setAttribute(HttpServletResponse.class.getName(), response);

        var csrfToken = csrfTokenRepository.loadToken(request);
        if (csrfToken == null) {
            csrfToken = csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(csrfToken, request, response);
        }
        flowScope.put(csrfToken.getParameterName(), csrfToken);

        LOGGER.debug("Starting registration sequence for [{}]", principal);
        val authorities = principal.getAttributes().keySet().stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList());
        val secAuth = new PreAuthenticatedAuthenticationToken(principal, authn.getCredentials(), authorities);
        secAuth.setAuthenticated(true);
        secAuth.setDetails(new PreAuthenticatedGrantedAuthoritiesWebAuthenticationDetails(request, authorities));
        SecurityContextHolder.getContext().setAuthentication(secAuth);
        var session = request.getSession(true);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextHolder.getContext());

        return null;
    }
}
