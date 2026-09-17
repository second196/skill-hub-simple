#!/usr/bin/env node
const { chmodSync, statSync } = require('node:fs')
const { resolve } = require('node:path')

const target = process.argv[2]
if (!target || process.platform === 'win32') process.exit(0)

try {
  const file = resolve(target)
  const stats = statSync(file)
  chmodSync(file, stats.mode | 0o111)
} catch (error) {
  console.warn(`Unable to mark ${target} executable: ${error.message}`)
}
