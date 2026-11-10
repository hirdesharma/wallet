package com.hirdesh.wallet.ledger.locking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hirdesh.wallet.account.AccountRepository;

/**
 * O-4: strategy chosen by config. Only CORRECT strategies exist in production code;
 * the deliberately broken ones used in the M3 experiment live in src/test.
 */
@Configuration(proxyBeanMethods = false)
public class LockingConfig {

    public enum Mode { PESSIMISTIC, OPTIMISTIC }

    @Bean
    AccountLocking accountLocking(@Value("${wallet.ledger.locking}") Mode mode, AccountRepository accounts) {
        return switch (mode) {
            case PESSIMISTIC -> new PessimisticLocking(accounts);
            case OPTIMISTIC -> new OptimisticLocking(accounts);
        };
    }
}
