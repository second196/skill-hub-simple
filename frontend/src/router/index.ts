import { createRouter, createWebHistory } from 'vue-router'
const EmptyRoute = { template: '<span />' }
export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: EmptyRoute },
    { path: '/discover/:id', component: EmptyRoute },
    { path: '/discover', component: EmptyRoute },
    { path: '/search', component: EmptyRoute },
    { path: '/publish', component: EmptyRoute },
    { path: '/observe/sessions', component: EmptyRoute },
    { path: '/observe/:slug', component: EmptyRoute },
    { path: '/observe', component: EmptyRoute },
    { path: '/skills/:slug', component: EmptyRoute }
  ]
})
