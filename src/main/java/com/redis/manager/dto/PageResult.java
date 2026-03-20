package com.redis.manager.dto;

import java.util.List;

/**
 * 分页结果封装类
 * 
 * @author Redis Manager
 * @version 1.0.0
 * @param <T> 数据类型
 */
public class PageResult<T> {
    
    /**
     * 当前页码（从1开始）
     */
    private int page;
    
    /**
     * 每页大小
     */
    private int size;
    
    /**
     * 总记录数
     */
    private long total;
    
    /**
     * 总页数
     */
    private int pages;
    
    /**
     * 当前页数据列表
     */
    private List<T> list;
    
    public PageResult() {
    }
    
    public PageResult(int page, int size, long total, List<T> list) {
        this.page = page;
        this.size = size;
        this.total = total;
        this.list = list;
        this.pages = (int) Math.ceil((double) total / size);
    }
    
    public int getPage() {
        return page;
    }
    
    public void setPage(int page) {
        this.page = page;
    }
    
    public int getSize() {
        return size;
    }
    
    public void setSize(int size) {
        this.size = size;
    }
    
    public long getTotal() {
        return total;
    }
    
    public void setTotal(long total) {
        this.total = total;
        if (this.size > 0) {
            this.pages = (int) Math.ceil((double) total / this.size);
        }
    }
    
    public int getPages() {
        return pages;
    }
    
    public void setPages(int pages) {
        this.pages = pages;
    }
    
    public List<T> getList() {
        return list;
    }
    
    public void setList(List<T> list) {
        this.list = list;
    }
    
    /**
     * 是否有上一页
     */
    public boolean hasPrevious() {
        return page > 1;
    }
    
    /**
     * 是否有下一页
     */
    public boolean hasNext() {
        return page < pages;
    }
}
