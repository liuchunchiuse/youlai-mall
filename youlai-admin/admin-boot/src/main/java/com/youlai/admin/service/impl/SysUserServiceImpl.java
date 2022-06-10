package com.youlai.admin.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.read.builder.ExcelReaderBuilder;
import com.alibaba.excel.read.builder.ExcelReaderSheetBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.common.collect.Lists;
import com.youlai.admin.common.constant.SystemConstants;
import com.youlai.admin.common.enums.GenderEnum;
import com.youlai.admin.component.listener.excel.UserImportListener;
import com.youlai.admin.convert.UserConvert;
import com.youlai.admin.dto.UserAuthDTO;
import com.youlai.admin.mapper.SysUserMapper;
import com.youlai.admin.pojo.dto.UserImportDTO;
import com.youlai.admin.pojo.entity.SysUser;
import com.youlai.admin.pojo.entity.SysUserRole;
import com.youlai.admin.pojo.form.UserForm;
import com.youlai.admin.pojo.po.UserFormPO;
import com.youlai.admin.pojo.po.UserPO;
import com.youlai.admin.pojo.query.UserPageQuery;
import com.youlai.admin.pojo.vo.user.LoginUserVO;
import com.youlai.admin.pojo.vo.user.UserExportVO;
import com.youlai.admin.pojo.vo.user.UserPageVO;
import com.youlai.admin.service.SysPermissionService;
import com.youlai.admin.service.SysUserRoleService;
import com.youlai.admin.service.SysUserService;
import com.youlai.common.base.IBaseEnum;
import com.youlai.common.web.utils.UserUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 用户业务实现类
 *
 * @author haoxr
 * @date 2022/1/14
 */
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private final PasswordEncoder passwordEncoder;
    private final SysUserRoleService sysUserRoleService;
    private final UserImportListener userImportListener;
    private final SysPermissionService permissionService;
    private final UserConvert userConvert;

    /**
     * 获取用户分页列表
     *
     * @param queryParams
     * @return
     */
    @Override
    public IPage<UserPageVO> listPageUsers(UserPageQuery queryParams) {

        // 参数构建
        int pageNum = queryParams.getPageNum();
        int pageSize = queryParams.getPageSize();
        Page<UserPO> page = new Page<>(pageNum, pageSize);

        // 查询数据
        Page<UserPO> userPagePO = this.baseMapper.listPageUsers(page, queryParams);

        // 实体转换
        Page<UserPageVO> voPage = userConvert.po2PageVO(userPagePO);

        return voPage;
    }

    /**
     * 获取用户表单详情
     *
     * @param userId
     * @return
     */
    @Override
    public UserForm getUserFormData(Long userId) {
        UserFormPO userFormPO = this.baseMapper.getUserFormData(userId);
        // 实体转换po->form
        UserForm userForm = userConvert.po2Form(userFormPO);
        return userForm;
    }

    /**
     * 新增用户
     *
     * @param userForm 用户表单对象
     * @return
     */
    @Override
    public boolean saveUser(UserForm userForm) {

        SysUser user = new SysUser();
        BeanUtil.copyProperties(userForm, user);

        user.setPassword(passwordEncoder.encode(SystemConstants.DEFAULT_USER_PASSWORD)); // 初始化默认密码
        boolean result = this.save(user);
        if (result) {
            Long userId = user.getId();
            List<Long> roleIds = user.getRoleIds();
            List<SysUserRole> sysUserRoles = Optional.ofNullable(roleIds).orElse(new ArrayList<>())
                    .stream().map(roleId -> new SysUserRole(userId, roleId))
                    .collect(Collectors.toList());
            if (CollectionUtil.isNotEmpty(sysUserRoles)) {
                sysUserRoleService.saveBatch(sysUserRoles);
            }
        }
        return result;
    }

    /**
     * 更新用户
     *
     * @param userId   用户ID
     * @param userForm 用户表单对象
     * @return
     */
    @Override
    @Transactional
    public boolean updateUser(Long userId, UserForm userForm) {
        SysUser user = this.getById(userId);
        Assert.isTrue(user != null, "用户不存在或已被删除");

        // 用户旧角色ID集合
        List<Long> oldRoleIds = sysUserRoleService.list(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId))
                .stream()
                .map(item -> item.getRoleId())
                .collect(Collectors.toList());

        // 用户新角色ID集合
        List<Long> newRoleIds = userForm.getRoleIds();

        // 新增的用户角色
        List<Long> addRoleIds = newRoleIds.stream().filter(roleId -> !oldRoleIds.contains(roleId)).collect(Collectors.toList());
        List<SysUserRole> addUserRoles = Optional.ofNullable(addRoleIds).orElse(new ArrayList<>())
                .stream().map(roleId -> new SysUserRole(userId, roleId))
                .collect(Collectors.toList());
        sysUserRoleService.saveBatch(addUserRoles);

        // 删除的用户角色
        List<Long> removeRoleIds = oldRoleIds.stream().filter(roleId -> !newRoleIds.contains(roleId)).collect(Collectors.toList());
        if (CollectionUtil.isNotEmpty(removeRoleIds)) {
            sysUserRoleService.remove(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getUserId, userId)
                    .in(SysUserRole::getRoleId, removeRoleIds)
            );
        }

        BeanUtil.copyProperties(userForm, user);

        boolean updateRes = this.updateById(user);
        return updateRes;
    }

    /**
     * 删除用户
     *
     * @param idsStr 用户ID，多个以英文逗号(,)分割
     * @return
     */
    @Override
    public boolean deleteUsers(String idsStr) {

        return false;

    }

    /**
     * 修改用户密码
     *
     * @param userId   用户ID
     * @param password 用户密码
     * @return
     */
    @Override
    public boolean updateUserPassword(Long userId, String password) {
        String encryptedPassword = passwordEncoder.encode(password);
        boolean result = this.update(new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getPassword, encryptedPassword)
        );

        return result;
    }

    /**
     * 根据用户名获取认证信息
     *
     * @param username
     * @return
     */
    @Override
    public UserAuthDTO getAuthInfoByUsername(String username) {
        UserAuthDTO userAuthInfo = this.baseMapper.getAuthInfoByUsername(username);
        return userAuthInfo;
    }


    /**
     * 导入用户
     *
     * @param userImportDTO
     * @return
     */
    @Transactional
    @Override
    public String importUsers(UserImportDTO userImportDTO) throws IOException {

        Long deptId = userImportDTO.getDeptId();
        List<Long> roleIds = Arrays.stream(userImportDTO.getRoleIds().split(",")).map(roleId -> Convert.toLong(roleId)).collect(Collectors.toList());
        InputStream inputStream = userImportDTO.getFile().getInputStream();

        ExcelReaderBuilder excelReaderBuilder = EasyExcel.read(inputStream, UserImportDTO.UserItem.class, userImportListener);
        ExcelReaderSheetBuilder sheet = excelReaderBuilder.sheet();
        List<UserImportDTO.UserItem> list = sheet.doReadSync();

        Assert.isTrue(CollectionUtil.isNotEmpty(list), "未检测到任何数据");

        // 有效数据集合
        List<UserImportDTO.UserItem> validDataList = list.stream()
                .filter(item -> StrUtil.isNotBlank(item.getUsername()))
                .collect(Collectors.toList());

        Assert.isTrue(CollectionUtil.isNotEmpty(validDataList), "未检测到有效数据");

        long distinctCount = validDataList.stream()
                .map(UserImportDTO.UserItem::getUsername)
                .distinct()
                .count();
        Assert.isTrue(validDataList.size() == distinctCount, "导入数据中有重复的用户名，请检查！");

        List<SysUser> saveUserList = Lists.newArrayList();

        StringBuilder errMsg = new StringBuilder();
        for (int i = 0; i < validDataList.size(); i++) {
            UserImportDTO.UserItem userItem = validDataList.get(i);

            String username = userItem.getUsername();
            if (StrUtil.isBlank(username)) {
                errMsg.append(StrUtil.format("第{}条数据导入失败，原因：用户名为空", i + 1));
                continue;
            }

            String nickname = userItem.getNickname();
            if (StrUtil.isBlank(nickname)) {
                errMsg.append(StrUtil.format("第{}条数据导入失败，原因：用户昵称为空", i + 1));
                continue;
            }

            SysUser user = new SysUser();
            user.setUsername(username);
            user.setNickname(nickname);
            user.setMobile(userItem.getMobile());
            user.setEmail(userItem.getEmail());
            user.setDeptId(deptId);
            // 默认密码
            user.setPassword(passwordEncoder.encode(SystemConstants.DEFAULT_USER_PASSWORD));
            // 性别转换
            Integer gender = (Integer) IBaseEnum.getValueByLabel(userItem.getGender(), GenderEnum.class);
            user.setGender(gender);

            saveUserList.add(user);
        }

        if (CollectionUtil.isNotEmpty(saveUserList)) {
            boolean result = this.saveBatch(saveUserList);
            Assert.isTrue(result, "导入数据失败，原因：保存用户出错");

            List<SysUserRole> userRoleList = new ArrayList<>();

            if (CollectionUtil.isNotEmpty(roleIds)) {

                roleIds.forEach(roleId -> {
                    userRoleList.addAll(
                            saveUserList.stream()
                                    .map(user -> new SysUserRole(user.getId(), roleId)).
                                    collect(Collectors.toList()));
                });
            }

            sysUserRoleService.saveBatch(userRoleList);
        }

        errMsg.append(StrUtil.format("一共{}条数据，成功导入{}条数据，导入失败数据{}条", list.size(), saveUserList.size(), list.size() - saveUserList.size()));
        return errMsg.toString();

    }

    /**
     * 获取导出用户列表
     *
     * @param queryParams
     * @return
     */
    @Override
    public List<UserExportVO> listExportUsers(UserPageQuery queryParams) {
        List<UserExportVO> list = this.baseMapper.listExportUsers(queryParams);
        return list;
    }

    /**
     * 获取当前登录用户信息
     *
     * @return
     */
    @Override
    public LoginUserVO getLoginUserInfo() {
        // 用户基本信息
        Long userId = UserUtils.getUserId();
        SysUser user = this.getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId,userId)
                .select(SysUser::getId,SysUser::getNickname, SysUser::getAvatar)
            );

        LoginUserVO loginUserVO  =  userConvert.entity2LoginUser(user);


        BeanUtil.copyProperties(user, loginUserVO);
        // 用户角色信息
        List<String> roles = UserUtils.getRoles();
        loginUserVO.setRoles(roles);
        // 用户按钮权限信息
        List<String> perms = permissionService.listBtnPermByRoles(roles);
        loginUserVO.setPerms(perms);

        return loginUserVO;
    }

}
