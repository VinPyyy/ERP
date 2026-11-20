<template>
  <router-view />
</template>

<style>
/* 全局 reset：Element Plus 不重置 body margin，浏览器默认 8px 会让
   100vh 页面溢出视口（登录页右侧出现滚动条即由此而来） */
html,
body,
#app {
  margin: 0;
  padding: 0;
  height: 100%;
}
</style>
