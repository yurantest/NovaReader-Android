// search-worker.js
importScripts('./foliate-js/text-walker.js', './foliate-js/search.js');

self.onmessage = async function(e) {
    const { strings, query, options } = e.data;
    const results = [];
    for (const result of search(strings, query, options)) {
        results.push(result);
    }
    self.postMessage(results);
};