<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{ content?: string }>();

interface AnswerBlock {
  type: "heading" | "paragraph" | "list";
  text?: string;
  items?: string[];
}

const blocks = computed<AnswerBlock[]>(() => {
  const clean = (props.content || "")
    .replace(/\s*\[chunk:\d+\]/g, "")
    .replace(/\s*\[(?:c(?:h(?:u(?:n(?:k(?::\d*)?)?)?)?)?)?$/i, "")
    .replace(/\r\n/g, "\n")
    .trim();
  const result: AnswerBlock[] = [];
  let paragraph: string[] = [];
  let items: string[] = [];
  const flushParagraph = () => {
    if (paragraph.length) {
      result.push({ type: "paragraph", text: paragraph.join("\n") });
      paragraph = [];
    }
  };
  const flushList = () => {
    if (items.length) {
      result.push({ type: "list", items });
      items = [];
    }
  };
  for (const rawLine of clean.split("\n")) {
    const line = rawLine.trim();
    if (!line) {
      flushParagraph();
      flushList();
      continue;
    }
    if (/^#{1,4}\s+/.test(line)) {
      flushParagraph();
      flushList();
      result.push({ type: "heading", text: line.replace(/^#{1,4}\s+/, "") });
    } else if (/^(?:[-*]|\d+[.)])\s+/.test(line)) {
      flushParagraph();
      items.push(line.replace(/^(?:[-*]|\d+[.)])\s+/, ""));
    } else {
      flushList();
      paragraph.push(line);
    }
  }
  flushParagraph();
  flushList();
  return result;
});
</script>

<template>
  <div class="answer-content">
    <template v-for="(block, index) in blocks" :key="index">
      <h4 v-if="block.type === 'heading'">{{ block.text }}</h4>
      <p v-else-if="block.type === 'paragraph'">{{ block.text }}</p>
      <ul v-else>
        <li v-for="item in block.items" :key="item">{{ item }}</li>
      </ul>
    </template>
  </div>
</template>
