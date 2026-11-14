package com.aqss.backend.api;

import com.aqss.backend.model.Space;
import com.aqss.backend.service.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Spaces and their threshold. */
@RestController
@RequestMapping("/api/v1/spaces")
@RequiredArgsConstructor
public class SpaceController {

    private final SpaceService spaces;

    @GetMapping
    public List<Space> list() {
        return spaces.findAll();
    }

    @GetMapping("/{code}")
    public Space get(@PathVariable String code) {
        return spaces.require(code);
    }

    @PutMapping("/{code}")
    public Space save(@PathVariable String code, @RequestBody SpaceService.SpaceRequest request) {
        return spaces.save(new SpaceService.SpaceRequest(code, request.name(), request.volumeM3(),
                request.purpose(), request.region(), request.ventilationEstimate(),
                request.occupancyHours(), request.thresholdPm25()));
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        spaces.delete(code);
        return ResponseEntity.noContent().build();
    }

    /**
     * Compute the threshold the room profile implies, without saving it.
     *
     * <p>The dashboard shows this before the operator commits, so "auto" is a suggestion they can
     * read and argue with — not a number that silently replaces theirs.
     */
    @GetMapping("/{code}/threshold/preview")
    public SpaceService.ThresholdPreview preview(@PathVariable String code) {
        return spaces.previewThreshold(code);
    }

    /** Compute and store the profile-derived threshold. */
    @PostMapping("/{code}/threshold")
    public SpaceService.ThresholdPreview apply(@PathVariable String code) {
        return spaces.applyComputedThreshold(code);
    }
}
