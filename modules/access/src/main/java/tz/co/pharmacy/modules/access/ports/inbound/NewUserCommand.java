package tz.co.pharmacy.modules.access.ports.inbound;

public record NewUserCommand(String name, String email, String password) {}
