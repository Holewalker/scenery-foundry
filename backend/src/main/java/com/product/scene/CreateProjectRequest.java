package com.product.scene;

/** Request body for {@code POST /api/projects}; owner is always taken from {@code Authentication}, never this body. */
public record CreateProjectRequest(String name) { }
