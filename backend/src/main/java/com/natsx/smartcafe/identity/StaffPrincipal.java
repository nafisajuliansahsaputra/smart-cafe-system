package com.natsx.smartcafe.identity;

import java.util.Set;
import java.util.UUID;

public record StaffPrincipal(UUID staffId, String login, String tokenHash, Set<String> roles) {}
