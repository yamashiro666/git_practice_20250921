package com.practice.client.tracking.infrastructure.persistence.token;

import java.util.Optional;

public interface TokenStore {
	Optional<String> load(); void save(String token);
}
