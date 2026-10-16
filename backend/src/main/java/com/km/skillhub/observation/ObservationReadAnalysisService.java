package com.km.skillhub.observation;

import java.util.Map;

/**
 * Read-time, read-only analysis over an observation query result.
 *
 * <p>No write path: the service never persists anything and never mutates the raw
 * input. It consumes {@code observation_session/turn/step} shaped raw maps plus the
 * platform skill/version catalog and returns the layered response.</p>
 */
public interface ObservationReadAnalysisService {

    /**
     * @param raw             original query result (dashboard / node detail / chain)
     * @param platformCatalog skill/version catalog; {@code null} or available=false
     *                        means the catalog could not be loaded
     * @return layered response whose {@code raw} is always the given input
     */
    ObservationReadResponse analyze(Map<String, Object> raw, Map<String, Object> platformCatalog);
}
