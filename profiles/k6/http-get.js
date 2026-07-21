import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    main: {
      executor: 'constant-arrival-rate',
      rate: __ENV.RATE_PER_SEC ? parseInt(__ENV.RATE_PER_SEC) : 50,
      timeUnit: '1s',
      duration: __ENV.DURATION_SEC ? __ENV.DURATION_SEC + 's' : '30s',
      preAllocatedVUs: 10,
      maxVUs: 100,
    },
  },
};

const TARGET_URL = __ENV.TARGET_URL || 'https://httpbin.org/get';

export default function () {
  const res = http.get(TARGET_URL);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
