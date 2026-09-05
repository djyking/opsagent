<script setup lang="ts">
import { ArrowLeft, ArrowRight, Pause, Play } from "@lucide/vue";
import { useAccessibleCarousel } from "@/composables/useAccessibleCarousel";
import DomainIllustration from "@/components/illustrations/DomainIllustration.vue";
import type { ExperienceStory } from "@/data/experience";
const props = defineProps<{ stories: ExperienceStory[]; label: string; interval?: number; variant?: 'auth' | 'hero' }>();
const c = useAccessibleCarousel(() => props.stories.length, props.interval);
const { activeIndex, playing, enabled, focusPaused } = c;
let pointerWantsPlay: boolean | undefined;
function togglePlayback(event: MouseEvent) {
  // Pointer focus arrives before click; preserve the action shown at pointerdown.
  const play = event.detail && pointerWantsPlay !== undefined
    ? pointerWantsPlay : !enabled.value || focusPaused.value;
  pointerWantsPlay = undefined;
  if (play) c.resume(); else c.pause();
}
</script>
<template>
  <section class="story-carousel" :class="`story-carousel-${variant || 'hero'}`" data-motion :data-playing="playing" :data-index="activeIndex" role="region" aria-roledescription="carousel" :aria-label="label" tabindex="0" @mouseenter="c.onPointerEnter" @mouseleave="c.onPointerLeave" @focusin="c.onFocusIn" @keydown="c.onKeydown">
    <div class="story-stage" :aria-live="playing ? 'off' : 'polite'" aria-atomic="true">
      <Transition name="story" mode="out-in">
        <article v-if="stories[activeIndex]" :key="stories[activeIndex]!.key" class="experience-slide" role="group" aria-roledescription="slide" :aria-label="`${activeIndex + 1} / ${stories.length} ${stories[activeIndex]!.label}`">
          <div class="experience-copy"><span class="experience-kicker">{{ stories[activeIndex]!.label }}</span><h2>{{ stories[activeIndex]!.title }}</h2><p>{{ stories[activeIndex]!.description }}</p><div class="experience-tags"><span v-for="tag in stories[activeIndex]!.tags" :key="tag">{{ tag }}</span></div><RouterLink v-if="stories[activeIndex]!.to" class="button primary story-cta" :to="stories[activeIndex]!.to!">{{ stories[activeIndex]!.action }}<ArrowRight :size="16" /></RouterLink></div>
          <DomainIllustration :kind="stories[activeIndex]!.illustration" />
        </article>
      </Transition>
    </div>
    <div class="carousel-controls">
      <span class="carousel-count"><strong>{{ String(activeIndex + 1).padStart(2, '0') }}</strong> / {{ String(stories.length).padStart(2, '0') }}</span>
      <div class="carousel-dots" aria-label="选择故事"><button v-for="(story, index) in stories" :key="story.key" type="button" :aria-label="`第 ${index + 1} 页：${story.label}`" :aria-pressed="activeIndex === index" @click="c.goTo(index)" /></div>
      <div class="carousel-navigation"><button type="button" class="icon-button" :aria-label="!enabled || focusPaused ? '播放轮播' : '暂停轮播'" @pointerdown="pointerWantsPlay = !enabled || focusPaused" @pointercancel="pointerWantsPlay = undefined" @click="togglePlayback"><Play v-if="!enabled || focusPaused" :size="16" /><Pause v-else :size="16" /></button><button type="button" class="icon-button" aria-label="上一页" @click="c.previous"><ArrowLeft :size="16" /></button><button type="button" class="icon-button" aria-label="下一页" @click="c.next"><ArrowRight :size="16" /></button></div>
    </div>
  </section>
</template>
