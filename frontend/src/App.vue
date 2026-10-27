<!--
  模块：根组件（App.vue）
  职责：只做壳——渲染路由出口，并用 out-in 过渡让页面切换不闪。
  页面内容全部由 views/ 下的视图负责，这里不放业务状态。
-->
<template>
  <RouterView v-slot="{ Component }">
    <Transition name="page" mode="out-in">
      <component :is="Component" />
    </Transition>
  </RouterView>
</template>

<style>
.page-enter-active,
.page-leave-active {
  transition: opacity 220ms ease, transform 220ms ease;
}

.page-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.page-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
