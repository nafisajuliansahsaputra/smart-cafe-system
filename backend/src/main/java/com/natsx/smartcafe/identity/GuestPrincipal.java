package com.natsx.smartcafe.identity;

import java.util.UUID;

public record GuestPrincipal(UUID guestId, UUID visitId, String tokenHash) {}
