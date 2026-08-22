package com.product.printgroup;

import java.util.UUID;

public final class PrintGroupDtos {
    private PrintGroupDtos() { }

    public record CreatePrintGroupDto(String name) { }

    public record PrintGroupDto(UUID id, UUID projectId, String name) { }
}
