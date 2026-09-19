const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('buddy', {
  onState: cb => ipcRenderer.on('state', (_e, json) => cb(JSON.parse(json))),
  setHitboxes: h => ipcRenderer.send('hitboxes', h),
  pet: () => ipcRenderer.send('pet'),
  menu: () => ipcRenderer.send('menu'),
  dragStart: () => ipcRenderer.send('drag-start'),
  dragEnd: () => ipcRenderer.send('drag-end'),
  answer: id => ipcRenderer.send('answer', id),
  log: msg => ipcRenderer.send('log', msg),
});
