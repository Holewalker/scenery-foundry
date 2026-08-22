package com.product.export;

/** Owner-scoped read model of a COMBINED_EXPORT job's client-visible state. {@code diagnostics} is the raw
 * {@code geometry_jobs.diagnostics} jsonb text (never null in the row; callers default an absent value to "{}"). */
public record CombinedExportStatus(String status, String errorCode, String errorMessage, String diagnostics) { }
