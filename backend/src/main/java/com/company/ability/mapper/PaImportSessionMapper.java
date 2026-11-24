package com.company.ability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.ability.entity.PaImportSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * 能力结构导入会话 Mapper（sql/55）。
 *
 * <p>会话表已在 {@code MyBatisPlusConfig.TENANT_TABLES} 登记，增删查改自动带 dimension_id。
 * 但「只能看自己的会话」是另一层条件，由服务层显式加 {@code resource_id} 实现。</p>
 */
@Mapper
public interface PaImportSessionMapper extends BaseMapper<PaImportSession> {
}
