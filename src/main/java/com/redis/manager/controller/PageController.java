package com.redis.manager.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 页面控制器
 * 返回Thymeleaf视图
 */
@Controller
public class PageController {

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/groups")
    public String groups() {
        return "groups";
    }

    @GetMapping("/groups/new")
    public String newGroup(Model model) {
        model.addAttribute("action", "create");
        return "group-edit";
    }

    @GetMapping("/groups/edit/{id}")
    public String editGroup(@PathVariable Long id, Model model) {
        model.addAttribute("action", "edit");
        model.addAttribute("groupId", id);
        return "group-edit";
    }

    @GetMapping("/clusters")
    public String clusters() {
        return "clusters";
    }

    @GetMapping("/clusters/{clusterId}")
    public String clusterDetail(@PathVariable Long clusterId, Model model) {
        model.addAttribute("clusterId", clusterId);
        return "cluster-detail";
    }

    @GetMapping("/monitor")
    public String monitor() {
        return "monitor-new";
    }

    @GetMapping("/monitor/physical")
    public String monitorPhysical() {
        return "monitor-physical";
    }

    @GetMapping("/monitor/instance")
    public String monitorInstance() {
        return "redirect:/monitor/cluster";
    }

    @GetMapping("/monitor/cluster")
    public String monitorCluster() {
        return "monitor-cluster";
    }

    @GetMapping("/config/templates")
    public String configTemplates() {
        return "config-templates";
    }

    @GetMapping("/config/templates/edit/{id}")
    public String editTemplate(@PathVariable Long id, Model model) {
        model.addAttribute("templateId", id);
        return "template-edit";
    }

    @GetMapping("/clusters/create")
    public String createCluster() {
        return "cluster-create";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/users")
    public String users() {
        return "users";
    }

    @GetMapping("/help")
    public String help() {
        return "help";
    }

    @GetMapping("/system/config")
    public String systemConfig() {
        return "system-config";
    }

    @GetMapping("/backup")
    public String backup() {
        return "backup";
    }

}
