package com.tencent.effect.beautykit.model;

/**
 * @Author: wkq
 * @Time: 2025/10/28 15:17
 * @Desc:  网络贴纸的数据
 */
public class NetMotionInfo {
    private String id;
    private String name;
    private String icon;
    private String resourc_path;
    private String resourc_path2;
    private String is_show;
    private boolean isSelected; // 当前是否选中状态

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getResourc_path() {
        return resourc_path;
    }

    public void setResourc_path(String resourc_path) {
        this.resourc_path = resourc_path;
    }

    public String getResourc_path2() {
        return resourc_path2;
    }

    public void setResourc_path2(String resourc_path2) {
        this.resourc_path2 = resourc_path2;
    }

    public String getIs_show() {
        return is_show;
    }

    public void setIs_show(String is_show) {
        this.is_show = is_show;
    }

    public boolean isSelected() {
        return isSelected;
    }

    public void setSelected(boolean selected) {
        isSelected = selected;
    }
}
