package com.modernstock.sale;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.auth.User;
import com.modernstock.auth.UserRepository;
import com.modernstock.business.BusinessContext;
import com.modernstock.business.BusinessMember;
import com.modernstock.business.BusinessMemberRepository;
import com.modernstock.member.RoleRepository;

import lombok.RequiredArgsConstructor;

/**
 * Who can take over a shared POS device with a PIN (FR-6.19): active members of the business who sell, work in the
 * branch selected here, and have set a PIN. The switch itself is POST /auth/pin-switch.
 */
@Service
@RequiredArgsConstructor
public class CashierSwitchService {

    public record Cashier(Long userId, String fullName) {
    }

    private final BusinessMemberRepository members;
    private final UserRepository users;
    private final RoleRepository roles;

    @Transactional(readOnly = true)
    public List<Cashier> cashiers() {
        BusinessContext.Context c = BusinessContext.require();
        Long branch = c.selectedBranchId();
        List<BusinessMember> active = members.findByBusinessIdAndStatusNotOrderByJoinedAt(c.businessId(), BusinessMember.Status.REMOVED).stream()
                .filter(m -> m.getStatus() == BusinessMember.Status.ACTIVE && !m.isDisabledByPlan())
                .filter(m -> branch == null || m.workBranchIds() == null || m.workBranchIds().contains(branch))
                .filter(m -> roles.findById(m.getRoleId())
                        .map(r -> r.getPermissions().stream().anyMatch(p -> "sale:create".equals(p.getCode()))).orElse(false))
                .toList();
        Map<Long, User> byId = users.findAllById(active.stream().map(BusinessMember::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return active.stream()
                .map(m -> byId.get(m.getUserId()))
                .filter(u -> u != null && u.getStatus() == User.Status.ACTIVE && u.getPinHash() != null)
                .map(u -> new Cashier(u.getId(), u.getFullName()))
                .sorted(Comparator.comparing(Cashier::fullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
