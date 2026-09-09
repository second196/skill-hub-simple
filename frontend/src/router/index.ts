import { createRouter, createWebHistory } from 'vue-router'
const EmptyRoute = { template: '<span />' }
export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: EmptyRoute },
    { path: '/search', component: EmptyRoute },
    { path: '/publish', component: EmptyRoute },
    { path: '/console', component: EmptyRoute },
    { path: '/skills/:slug', component: EmptyRoute }
  ]
})
