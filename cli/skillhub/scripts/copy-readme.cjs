#!/usr/bin/env node
const { copyFileSync, mkdirSync } = require('node:fs')
const { resolve } = require('node:path')

const source = resolve(__dirname, '..', 'README.md')
const destinationDirectory = resolve(__dirname, '..', 'dist')
const destination = resolve(destinationDirectory, 'README.md')

mkdirSync(destinationDirectory, { recursive: true })
copyFileSync(source, destination)
console.log(`Copied README.md to ${destination}`)
