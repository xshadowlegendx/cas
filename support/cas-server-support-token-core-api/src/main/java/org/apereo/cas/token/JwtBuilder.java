package org.apereo.cas.token;

import org.apereo.cas.CentralAuthenticationService;
import org.apereo.cas.authentication.principal.WebApplicationServiceFactory;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.services.RegisteredService;
import org.apereo.cas.services.RegisteredServiceAccessStrategyUtils;
import org.apereo.cas.services.RegisteredServiceCipherExecutor;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.services.UnauthorizedServiceException;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.util.LoggingUtils;
import org.apereo.cas.util.crypto.CipherExecutor;
import org.apereo.cas.util.function.FunctionUtils;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * This is {@link JwtBuilder}.
 *
 * @author Misagh Moayyed
 * @since 6.1.0
 */
@Slf4j
@RequiredArgsConstructor
@Getter
public class JwtBuilder {
    private final CipherExecutor<Serializable, String> defaultTokenCipherExecutor;

    private final ServicesManager servicesManager;

    private final RegisteredServiceCipherExecutor registeredServiceCipherExecutor;

    private final CasConfigurationProperties casProperties;

    public JwtBuilder(final CipherExecutor<Serializable, String> cipherExecutor,
                      final ServicesManager servicesManager, final CasConfigurationProperties properties) {
        this(cipherExecutor, servicesManager, RegisteredServiceCipherExecutor.noOp(), properties);
    }

    /**
     * Parse jwt.
     *
     * @param jwt the jwt
     * @return the jwt
     */
    public static JWTClaimsSet parse(final String jwt) {
        try {
            return JWTParser.parse(jwt).getJWTClaimsSet();
        } catch (final Exception e) {
            LOGGER.trace("Unable to parse [{}] JWT; trying JWT claim set...", jwt);
            try {
                return JWTClaimsSet.parse(jwt);
            } catch (final Exception ex) {
                LoggingUtils.error(LOGGER, ex);
                throw new IllegalArgumentException("Unable to parse JWT");
            }
        }
    }

    /**
     * Build plain string.
     *
     * @param claimsSet         the claims set
     * @param registeredService the registered service
     * @return the jwt
     */
    public static String buildPlain(final JWTClaimsSet claimsSet,
                                    final Optional<RegisteredService> registeredService) {
        val header = new PlainHeader.Builder().type(JOSEObjectType.JWT);
        registeredService.ifPresent(svc ->
            header.customParam(RegisteredServiceCipherExecutor.CUSTOM_HEADER_REGISTERED_SERVICE_ID, svc.getId()));
        return new PlainJWT(header.build(), claimsSet).serialize();
    }

    /**
     * Unpack jwt claims set.
     *
     * @param jwtJson the jwt json
     * @return the jwt claims set
     */
    public JWTClaimsSet unpack(final String jwtJson) {
        return unpack(Optional.empty(), jwtJson);
    }

    /**
     * Unpack jwt.
     *
     * @param service the service
     * @param jwtJson the jwt json
     * @return the string
     */
    public JWTClaimsSet unpack(final Optional<RegisteredService> service, final String jwtJson) {
        return FunctionUtils.doUnchecked(() -> {
            service.ifPresent(svc -> {
                LOGGER.trace("Located service [{}] in service registry", svc);
                RegisteredServiceAccessStrategyUtils.ensureServiceAccessIsAllowed(svc);
            });

            val jwt = JWTParser.parse(jwtJson);
            if (jwt instanceof SignedJWT || jwt instanceof EncryptedJWT) {
                if (service.isPresent()) {
                    val registeredService = service.get();
                    LOGGER.trace("Locating service signing and encryption keys for [{}]", registeredService.getServiceId());
                    if (registeredServiceCipherExecutor.supports(registeredService)) {
                        LOGGER.trace("Decoding JWT based on keys provided by service [{}]", registeredService.getServiceId());
                        return parse(registeredServiceCipherExecutor.decode(jwtJson, Optional.of(registeredService)));
                    }
                }

                return FunctionUtils.doIf(defaultTokenCipherExecutor.isEnabled(),
                    () -> {
                        LOGGER.trace("Decoding JWT based on default global keys");
                        return parse(defaultTokenCipherExecutor.decode(jwtJson));
                    }, () -> {
                        throw new IllegalArgumentException("Unable to validate JWT signature");
                    }).get();
            }
            return parse(jwtJson);
        });
    }

    /**
     * Build JWT.
     *
     * @param payload the payload
     * @return the jwt
     * @throws Exception the exception
     */
    public String build(final JwtRequest payload) throws Exception {
        val serviceAudience = payload.getServiceAudience();
        Objects.requireNonNull(payload.getIssuer(), "Issuer cannot be undefined");
        val claims = new JWTClaimsSet.Builder()
            .audience(new ArrayList<>(serviceAudience))
            .issuer(payload.getIssuer())
            .jwtID(payload.getJwtId())
            .issueTime(payload.getIssueDate())
            .subject(payload.getSubject());

        payload.getAttributes()
            .entrySet()
            .stream()
            .filter(entry -> !entry.getKey().startsWith(CentralAuthenticationService.NAMESPACE))
            .forEach(entry -> {
                val value = entry.getValue();
                var claimValue = value.size() == 1 ? CollectionUtils.firstElement(value).get() : value;
                if (claimValue instanceof ZonedDateTime) {
                    claimValue = claimValue.toString();
                }
                claims.claim(entry.getKey(), claimValue);
            });
        claims.expirationTime(payload.getValidUntilDate());
        val claimsSet = finalizeClaims(claims.build(), payload);
        
        LOGGER.trace("Locating service [{}] in service registry", serviceAudience);
        val registeredService = payload.getRegisteredService()
            .orElseGet(() -> serviceAudience.stream()
                .map(this::locateRegisteredService)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> UnauthorizedServiceException.denied("Unable to locate registered service via any of %s".formatted(serviceAudience))));
        return build(registeredService, claimsSet);
    }

    /**
     * Build JWT.
     *
     * @param registeredService the registered service
     * @param claimsSet         the claims set
     * @return the string
     */
    public String build(final RegisteredService registeredService,
                        final JWTClaimsSet claimsSet) {

        RegisteredServiceAccessStrategyUtils.ensureServiceAccessIsAllowed(registeredService);

        val jwtJson = claimsSet.toString();
        LOGGER.debug("Generated JWT [{}]", jwtJson);

        LOGGER.trace("Locating service specific signing and encryption keys for service [{}]", registeredService.getName());
        if (registeredServiceCipherExecutor.supports(registeredService)) {
            LOGGER.trace("Encoding JWT based on keys provided by service [{}]", registeredService.getServiceId());
            return registeredServiceCipherExecutor.encode(jwtJson, Optional.of(registeredService));
        }

        if (defaultTokenCipherExecutor.isEnabled()) {
            LOGGER.trace("Encoding JWT based on default global keys for service [{}]", registeredService.getName());
            return defaultTokenCipherExecutor.encode(jwtJson);
        }
        val token = buildPlain(claimsSet, Optional.of(registeredService));
        LOGGER.trace("Generating plain JWT as the ticket: [{}]", token);
        return token;
    }

    protected RegisteredService locateRegisteredService(final String serviceAudience) {
        val service = new WebApplicationServiceFactory().createService(serviceAudience);
        return servicesManager.findServiceBy(service);
    }

    protected JWTClaimsSet finalizeClaims(final JWTClaimsSet claimsSet, final JwtRequest payload) throws Exception {
        return claimsSet;
    }
    
    /**
     * The type Jwt request that allows the builder to create JWTs.
     */
    @SuperBuilder
    @Getter
    @ToString
    @SuppressWarnings("JavaUtilDate")
    public static class JwtRequest {
        private final String jwtId;

        private final Set<String> serviceAudience;

        @Builder.Default
        private final Date issueDate = new Date();

        private final String subject;

        private final Date validUntilDate;

        private final String issuer;

        @Builder.Default
        private final Map<String, List<Object>> attributes = new LinkedHashMap<>();

        @Builder.Default
        private Optional<RegisteredService> registeredService = Optional.empty();
    }
}
