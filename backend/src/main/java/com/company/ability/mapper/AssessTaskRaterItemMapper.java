package com.company.ability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.ability.entity.AssessTaskRaterItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 360 测评人按能力项指派 Mapper（sql/60）。
 *
 * <p>没有声明进 {@code MyBatisPlusConfig.TENANT_TABLES}：本表的租户归属由 {@code task_id}
 * 间接决定（任务本身带 dimension_id），直接按 dimension_id 过滤反而要求这列冗余存一份，
 * 而它一旦跟任务不同步就是越权读的入口。写入时由服务层从任务头取 taskId，不存在传错的可能。</p>
 */
@Mapper
public interface AssessTaskRaterItemMapper extends BaseMapper<AssessTaskRaterItem> {
}
