package com.wuxi.ai.rag;

/**
 * PDF 的一页原始文本。
 *
 * 为什么保留页码：后面做引用溯源时要说「见《ER50 维护手册》P37」，
 * 如果早期就丢掉页信息，后面补不回来。
 */
public record Page(int number, String text) {
}
