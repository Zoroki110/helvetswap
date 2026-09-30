package com.digitalasset.quickstart.security;

import com.digitalasset.quickstart.security.oauth2.AuthClientRegistrationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;

/**
 * DevNet stub configuration for Admin API dependencies.
 * Provides minimal beans so AdminApiImpl can instantiate without full OAuth2 client setup.
 *
 * This is a transitional config - replace with full OAuth2 client registration for production.
 */
@Configuration
@Profile("devnet")
public class DevNetAdminStubConfig {

  @Bean
  @ConditionalOnMissingBean(UserDetailsManager.class)
  public InMemoryUserDetailsManager userDetailsManager(
      @Value("${admin.username:devnet-admin}") String u,
      @Value("${admin.password}") String p) {
    return new InMemoryUserDetailsManager(
        User.withUsername(u).password("{noop}"+p).roles("ADMIN").build());
  }

  @Bean
  @ConditionalOnMissingBean(AuthClientRegistrationRepository.class)
  public AuthClientRegistrationRepository authClientRegistrationRepository() {
    // Minimal stub - AdminApiImpl checks for presence but doesn't use it for DevNet
    return new AuthClientRegistrationRepository() {
      @Override
      public String registerClient(Client client) throws IllegalArgumentException {
        return "devnet-stub-registration-id";
      }

      @Override
      public void removeClientRegistration(String tenantId, String clientId) {
        // No-op for DevNet stub
      }

      @Override
      public void removeClientRegistrations(String tenantId) {
        // No-op for DevNet stub
      }

      @Override
      public java.util.Collection<Client> getClientRegistrations() {
        return java.util.Collections.emptyList();
      }

      @Override
      public String getLoginLink(String clientRegistrationId) {
        return "http://localhost:8080/oauth2/authorization/devnet";
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(AuthenticatedUserProvider.class)
  public AuthenticatedUserProvider authenticatedUserProvider() {
    // For DevNet testing - returns empty user (unauthenticated)
    return () -> java.util.Optional.empty();
  }
}
