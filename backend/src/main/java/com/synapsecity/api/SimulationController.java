package com.synapsecity.api;

import com.synapsecity.simulation.metrics.MetricsSample;
import com.synapsecity.simulation.scenarios.ComparisonRunner;
import com.synapsecity.simulation.scenarios.ScenarioPreset;
import com.synapsecity.websocket.protocol.Messages;
import com.synapsecity.websocket.protocol.ProtocolMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Read-only HTTP views of the live simulation plus scripted experiments. Control happens over WebSocket. */
@RestController
@RequestMapping("/api")
public class SimulationController {

    private final SimulationService service;

    public SimulationController(SimulationService service) {
        this.service = service;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Messages.StateMessage s = service.stateSnapshot();
        return Map.of("status", "UP", "tick", s.tick(), "simTime", s.time(), "vehicles", s.metrics().active());
    }

    @GetMapping(value = "/city", produces = MediaType.APPLICATION_JSON_VALUE)
    public String city() {
        return service.cityJson();
    }

    @GetMapping("/state")
    public Messages.StateMessage state() {
        return service.stateSnapshot();
    }

    @GetMapping("/scenarios")
    public List<Messages.ScenarioDto> scenarios() {
        return ProtocolMapper.scenarios();
    }

    /** Exports the sampled time series of the live run as CSV (simulated data, not real traffic). */
    @GetMapping(value = "/metrics/series.csv", produces = "text/csv")
    public ResponseEntity<String> seriesCsv() {
        StringBuilder sb = new StringBuilder("# Synapse City simulated metrics (not real-world traffic data)\n");
        sb.append("time_s,active,avg_speed_mps,congestion_index,queued,throughput_per_min,avg_trip_s,avg_trip_wait_s,completed,travel_time_index\n");
        for (MetricsSample m : service.series()) {
            sb.append(String.format("%.1f,%d,%.3f,%.2f,%d,%.2f,%.2f,%.2f,%d,%.3f%n", m.time(), m.active(), m.avgSpeed(),
                    m.congestionIndex(), m.queued(), m.throughputPerMin(), m.avgTripTime(), m.avgTripWait(),
                    m.completed(), m.travelTimeIndex()));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=synapse-city-metrics.csv")
                .body(sb.toString());
    }

    @PostMapping("/experiments/compare/{scenario}")
    public ComparisonRunner.ComparisonResult compare(@PathVariable String scenario,
                                                     @RequestParam(defaultValue = "600") double seconds) throws Exception {
        ScenarioPreset preset;
        try {
            preset = ScenarioPreset.valueOf(scenario.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Unknown scenario " + scenario);
        }
        if (seconds < 60 || seconds > 1800) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "seconds must be within [60, 1800]");
        }
        return service.compareBlocking(preset, seconds);
    }
}
