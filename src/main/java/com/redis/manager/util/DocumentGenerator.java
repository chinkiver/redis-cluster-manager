package com.redis.manager.util;

import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;

import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;

/**
 * Word文档生成工具类
 * 
 * @author Redis Manager
 * @version 1.0.0
 */
public class DocumentGenerator {

    private XWPFDocument document;
    private int headingNum = 1;

    public DocumentGenerator() {
        this.document = new XWPFDocument();
    }

    /**
     * 添加标题
     */
    public void addTitle(String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(22);
        run.setFontFamily("黑体");
        addEmptyLine();
    }

    /**
     * 添加一级标题
     */
    public void addHeading1(String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("Heading1");
        XWPFRun run = paragraph.createRun();
        run.setText(headingNum++ + ". " + text);
        run.setBold(true);
        run.setFontSize(16);
        run.setFontFamily("黑体");
        run.setColor("DC382C");
        addEmptyLine();
    }

    /**
     * 添加二级标题
     */
    public void addHeading2(String text) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(14);
        run.setFontFamily("黑体");
        addEmptyLine();
    }

    /**
     * 添加三级标题
     */
    public void addHeading3(String text) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(12);
        run.setFontFamily("黑体");
    }

    /**
     * 添加正文段落
     */
    public void addParagraph(String text) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontSize(11);
        run.setFontFamily("宋体");
    }

    /**
     * 添加空行
     */
    public void addEmptyLine() {
        document.createParagraph();
    }

    /**
     * 添加带样式的段落
     */
    public void addStyledParagraph(String text, boolean bold, int fontSize, String color) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setFontSize(fontSize);
        if (color != null) {
            run.setColor(color);
        }
        run.setFontFamily("宋体");
    }

    /**
     * 创建表格
     */
    public XWPFTable createTable(int rows, int cols) {
        XWPFTable table = document.createTable(rows, cols);
        table.setWidth("100%");
        return table;
    }

    /**
     * 设置表格单元格内容
     */
    public void setCellText(XWPFTableCell cell, String text, boolean bold) {
        cell.removeParagraph(0);
        XWPFParagraph paragraph = cell.addParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setFontSize(10);
        run.setFontFamily("宋体");
        paragraph.setAlignment(ParagraphAlignment.CENTER);
    }

    /**
     * 添加代码块
     */
    public void addCodeBlock(String code) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(400);
        XWPFRun run = paragraph.createRun();
        run.setText(code);
        run.setFontSize(9);
        run.setFontFamily("Consolas");
        run.setColor("333333");
    }

    /**
     * 添加警告信息
     */
    public void addWarning(String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(200);
        XWPFRun run = paragraph.createRun();
        run.setText("⚠️ " + text);
        run.setBold(true);
        run.setFontSize(11);
        run.setColor("FF6B6B");
        run.setFontFamily("宋体");
    }

    /**
     * 添加提示信息
     */
    public void addTip(String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(200);
        XWPFRun run = paragraph.createRun();
        run.setText("💡 " + text);
        run.setFontSize(11);
        run.setColor("4ECDC4");
        run.setFontFamily("宋体");
    }

    /**
     * 保存文档
     */
    public void save(String filePath) throws IOException {
        try (FileOutputStream out = new FileOutputStream(filePath)) {
            document.write(out);
        }
    }

    /**
     * 关闭文档
     */
    public void close() throws IOException {
        document.close();
    }
}
