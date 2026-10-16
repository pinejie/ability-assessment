package com.company.ability.controller;

import com.company.ability.dto.AssessArchiveDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.AssessArchiveService;
import com.company.ability.vo.AssessSubjectVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 测评归档与结果查询
 */
@Tag(name = "测评归档", description = "泛微流程归档后的测评计算与结果查询")
@RestController
@RequestMapping("/api/v1/assess")
@RequiredArgsConstructor
public class AssessArchiveController {

    private final AssessArchiveService assessArchiveService;

    @Operation(summary = "归档测评（幂等）")
    @PostMapping("/archive")
    public Result<AssessSubjectVO> archive(@Valid @RequestBody AssessArchiveDTO.ArchiveRequest request) {
        return Result.success("归档成功", assessArchiveService.archive(request));
    }

    @Operation(summary = "查询测评结果详情")
    @GetMapping("/subjects/{id}")
    public Result<AssessSubjectVO> getSubject(@PathVariable Long id) {
        return Result.success(assessArchiveService.getSubjectDetail(id));
    }
}
