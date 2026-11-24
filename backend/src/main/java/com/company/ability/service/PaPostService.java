package com.company.ability.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.PaPostCreateDTO;
import com.company.ability.dto.PaPostUpdateDTO;
import com.company.ability.entity.PaPost;
import com.company.ability.vo.PaPostVO;

import java.util.List;

/**
 * 测评岗位服务。
 *
 * <p>岗位是整套能力模型的锚点：所有类别/能力项/等级/分值区间/人员映射都挂在它下面，
 * 所以这里的删除校验比别的模块都重 —— 有引用就一律不许删。</p>
 */
public interface PaPostService extends IService<PaPost> {

    Long createPost(PaPostCreateDTO dto);

    void updatePost(PaPostUpdateDTO dto);

    void deletePost(Long id);

    PaPostVO getPostById(Long id);

    PageResult<PaPostVO> pagePosts(PageRequest pageRequest, Long departmentId, String keyword,
                                   Integer status, Boolean generic);

    /** 下拉用：只返回启用中的岗位，带部门名 */
    List<PaPostVO> listPosts(Long departmentId);

    /**
     * 下拉用：启用中的<b>全公司通用岗位</b>（{@code department_id IS NULL}）。
     *
     * <p>发起页要把它和「被测人所在部门的岗位」合并成候选人选 ——
     * 分开两个接口而不是让 {@code listPosts(null)} 兼任：那个重载现在的语义是
     * 「不限部门，全都要」，混进通用语义会让老调用方（配置页下拉）莫名其妙多出通用岗位。</p>
     */
    List<PaPostVO> listGenericPosts();
}
