package com.product.scene;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Externalizes ADR-0007's transitional compatibility switch: {@code false} while a pre-upgrade client may
 * still omit {@code version} on a scene PUT (bypasses the conflict check, D2), {@code true} once that
 * client has shipped (PR5, once PR2's frontend always sends {@code version}). Registered via
 * {@code @ConfigurationPropertiesScan} on {@code SceneryFoundryApplication} — same reasoning as
 * {@link com.product.piecesexport.PiecesExportProperties}: a component-scanned constructor-bound properties
 * record would resolve through ordinary bean autowiring instead of the configuration-properties binder. */
@ConfigurationProperties(prefix = "app.scene")
public record SceneProperties(@DefaultValue("false") boolean requireVersion) { }
