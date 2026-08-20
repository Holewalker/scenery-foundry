package com.product.piecesexport;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Externalizes the pieces-export uncompressed-bytes cap (design's Open Question: 512 MiB default, no ADR
 * backing, tunable without a code change once real corpora exist). Registered via
 * {@code @ConfigurationPropertiesScan} on {@code SceneryFoundryApplication} — NOT {@code @Component}: a
 * component-scanned constructor-bound properties record would instead be resolved through ordinary bean
 * autowiring (which fails looking for a {@code long} bean) rather than the configuration-properties binder. */
@ConfigurationProperties(prefix = "app.piecesexport")
public record PiecesExportProperties(@DefaultValue("536870912") long maxUncompressedBytes) {
}
