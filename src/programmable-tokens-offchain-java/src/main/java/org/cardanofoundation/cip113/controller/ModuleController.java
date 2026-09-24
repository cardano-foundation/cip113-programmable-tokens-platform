package org.cardanofoundation.cip113.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.Module;
import org.cardanofoundation.cip113.service.ModuleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("${apiPrefix}/modules")
@RequiredArgsConstructor
@Slf4j
public class ModuleController {

    private final ModuleService moduleService;

    /**
     * Get all modules
     *
     * @return list of all modules with their validators
     */
    @GetMapping
    public ResponseEntity<List<Module>> getAllModules() {
        log.debug("GET /modules - fetching all modules");
        List<Module> modules = moduleService.getAllModules();
        return ResponseEntity.ok(modules);
    }

    /**
     * Get a specific module by ID (folder name)
     *
     * @param id the module ID (folder name in modules directory)
     * @return the module with its validators or 404 if not found
     */
    @GetMapping("/{id}")
    public ResponseEntity<Module> getModuleById(@PathVariable String id) {
        log.debug("GET /modules/{} - fetching module by id", id);
        return moduleService.getModuleById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
