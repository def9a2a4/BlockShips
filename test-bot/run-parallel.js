const { spawn } = require('child_process')
const path = require('path')

// Test names matching TESTS registry in test-bot.js
const TESTS = ['smallship', 'bigship', 'smallairship', 'custom_ship', 'custom_airship']

// Stagger delay between bot connections to avoid throttling
// ViaVersion needs extra time between connections to avoid protocol errors
const CONNECTION_DELAY_MS = 24000

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

async function runParallel() {
  console.log('Starting parallel test execution...')
  console.log(`Running ${TESTS.length} tests in parallel\n`)

  const startTime = Date.now()

  // Spawn bots with staggered connections to avoid throttling
  const promises = []
  for (let index = 0; index < TESTS.length; index++) {
    const testName = TESTS[index]
    const username = `TestBot${testName}`

    const env = {
      ...process.env,
      BOT_INDEX: index.toString(),
      TEST_NAME: testName,
      MC_USERNAME: username
    }

    console.log(`[${testName}] Starting ${username} at X=${index * 50}`)

    const promise = new Promise((resolve) => {
      const child = spawn('node', [path.join(__dirname, 'test-bot.js')], {
        env,
        stdio: ['ignore', 'pipe', 'pipe']
      })

      let output = ''

      child.stdout.on('data', (data) => {
        const lines = data.toString().split('\n')
        lines.forEach(line => {
          if (line.trim()) {
            output += line + '\n'
            // Only show important lines during parallel execution
            if (line.includes('PASS:') || line.includes('FAIL:') || line.includes('error')) {
              console.log(`[${testName}] ${line}`)
            }
          }
        })
      })

      child.stderr.on('data', (data) => {
        console.error(`[${testName}] ERROR: ${data}`)
      })

      child.on('exit', (code) => {
        resolve({
          testName,
          passed: code === 0,
          exitCode: code,
          output
        })
      })
    })

    promises.push(promise)

    // Stagger connections to avoid server throttling
    if (index < TESTS.length - 1) {
      await sleep(CONNECTION_DELAY_MS)
    }
  }

  const results = await Promise.all(promises)

  const elapsed = ((Date.now() - startTime) / 1000).toFixed(1)
  const passed = results.filter(r => r.passed)
  const failed = results.filter(r => !r.passed)

  console.log('\n' + '='.repeat(50))
  console.log('PARALLEL TEST RESULTS')
  console.log('='.repeat(50))

  results.forEach(r => {
    const status = r.passed ? 'PASS' : 'FAIL'
    console.log(`  ${status}: ${r.testName}`)
  })

  console.log('')
  console.log(`Completed in ${elapsed}s`)
  console.log(`${passed.length} passed, ${failed.length} failed`)

  if (failed.length > 0) {
    console.log('\nFailed test outputs:')
    failed.forEach(r => {
      console.log(`\n--- ${r.testName} ---`)
      console.log(r.output)
    })
  }

  process.exit(failed.length > 0 ? 1 : 0)
}

runParallel().catch(err => {
  console.error('Parallel runner error:', err)
  process.exit(1)
})
