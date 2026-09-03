package com.km.skillhub.governance.service;

import com.km.skillhub.governance.model.entity.AccountEntity;
import com.km.skillhub.mapper.governance.AccountMapper;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AccountUserDetailsService implements UserDetailsService {

    private final AccountMapper accountMapper;

    public AccountUserDetailsService(AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AccountEntity account = accountMapper.findByUsername(username);
        if (account == null || !account.isEnabled()) {
            throw new UsernameNotFoundException("Invalid credentials");
        }
        return User.withUsername(account.getUsername())
                .password(account.getPasswordHash())
                .authorities("ROLE_USER")
                .disabled(!account.isEnabled())
                .build();
    }
}
