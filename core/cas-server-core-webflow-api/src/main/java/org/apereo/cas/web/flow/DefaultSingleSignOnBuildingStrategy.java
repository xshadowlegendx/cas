package org.apereo.cas.web.flow;

import org.apereo.cas.CentralAuthenticationService;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.AuthenticationResult;
import org.apereo.cas.authentication.PrincipalException;
import org.apereo.cas.ticket.InvalidTicketException;
import org.apereo.cas.ticket.Ticket;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.registry.TicketRegistrySupport;
import org.apereo.cas.util.LoggingUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;

/**
 * This is {@link DefaultSingleSignOnBuildingStrategy}.
 *
 * @author Misagh Moayyed
 * @since 7.1.0
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultSingleSignOnBuildingStrategy implements SingleSignOnBuildingStrategy {
    private final TicketRegistrySupport ticketRegistrySupport;
    private final CentralAuthenticationService centralAuthenticationService;

    @Override
    public Ticket buildTicketGrantingTicket(final AuthenticationResult authenticationResult,
                                            final Authentication authentication,
                                            final String ticketGrantingTicket) {
        try {
            return shouldIssueTicketGrantingTicket(authentication, ticketGrantingTicket)
                ? createTicketGrantingTicket(authenticationResult, ticketGrantingTicket)
                : updateTicketGrantingTicket(authentication, ticketGrantingTicket);
        } catch (final Throwable e) {
            LoggingUtils.error(LOGGER, e);
            if (e instanceof final PrincipalException pe) {
                throw pe;
            }
            throw new InvalidTicketException(ticketGrantingTicket);
        }
    }

    protected Ticket createTicketGrantingTicket(final AuthenticationResult authenticationResult,
                                                final String ticketGrantingTicket) throws Throwable {
        if (StringUtils.isNotBlank(ticketGrantingTicket)) {
            LOGGER.trace("Removing existing ticket-granting ticket [{}]", ticketGrantingTicket);
            ticketRegistrySupport.getTicketRegistry().deleteTicket(ticketGrantingTicket);
        }
        LOGGER.trace("Attempting to issue a new ticket-granting ticket...");
        return centralAuthenticationService.createTicketGrantingTicket(authenticationResult);
    }

    protected Ticket updateTicketGrantingTicket(final Authentication authentication, final String ticketGrantingTicket) throws Exception {
        LOGGER.debug("Updating existing ticket-granting ticket [{}]...", ticketGrantingTicket);
        val tgt = ticketRegistrySupport.getTicketRegistry().getTicket(ticketGrantingTicket, TicketGrantingTicket.class);
        tgt.getAuthentication().updateAttributes(authentication);
        return ticketRegistrySupport.getTicketRegistry().updateTicket(tgt);
    }

    protected boolean shouldIssueTicketGrantingTicket(final Authentication authentication,
                                                      final String ticketGrantingTicket) throws Exception {
        LOGGER.trace("Located ticket-granting ticket in the context. Retrieving associated authentication");
        val authenticationFromTgt = ticketRegistrySupport.getAuthenticationFrom(ticketGrantingTicket);

        if (authenticationFromTgt == null) {
            LOGGER.debug("Authentication session associated with [{}] is no longer valid", ticketGrantingTicket);
            if (StringUtils.isNotBlank(ticketGrantingTicket)) {
                ticketRegistrySupport.getTicketRegistry().deleteTicket(ticketGrantingTicket);
            }
            return true;
        }

        if (authentication.isEqualTo(authenticationFromTgt)) {
            LOGGER.debug("Resulting authentication matches the authentication from context");
            return false;
        }
        LOGGER.debug("Resulting authentication is different from the context");
        return true;
    }
}
