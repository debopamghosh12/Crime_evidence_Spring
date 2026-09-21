package com.blockevidence.backend.security;

import com.blockevidence.backend.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Bridges UserRepository to Spring Security's DaoAuthenticationProvider, which AuthService uses to
 * check a login. Only the login path uses this; every later request is authenticated from the JWT
 * alone and does not hit the database.
 *
 * <p>{@code disabled(!enabled)} makes the provider reject deactivated accounts (A4) before it even
 * compares the password.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public DatabaseUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmailIgnoreCase(email)
                .map(user -> org.springframework.security.core.userdetails.User.builder()
                        .username(user.getEmail())
                        .password(user.getPasswordHash())
                        .authorities(user.getRole().authority())
                        .disabled(!user.isEnabled())
                        .build())
                // The provider converts this to BadCredentialsException, so callers cannot tell
                // "no such user" from "wrong password".
                .orElseThrow(() -> new UsernameNotFoundException("No such user"));
    }
}
