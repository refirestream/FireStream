# FireStream

**FireStream ships with no extensions or sources. It is a media discovery tool.**

**⚠⚠ Community extensions you install can run arbitrary code within the app to display ads, steal your files, or run malware. ⚠⚠**
**You are responsible for the extensions you install — open-source extensions are recommended.**


FireStream is a (mostly vibe-coded) fork of [CloudStream](https://github.com/recloudstream/cloudstream) with a different philosophy on handling extensions. It specializes in movie and TV show discovery.
Downloader AFTVNews Code: 3425250

<a id="features"></a>

## Features

+ **Clean and simple to use** — only a single provider needed: TheMovieDB
+ **Sources run in the background** to find content for you
+ **Compatible** with existing CloudStream extensions


<a id="philosophy"></a>

## Philosophy: Sources, not Providers

CloudStream is built around **Providers**, which allows great flexibility and endless possibilities.

However, this flexibility spreads content across multiple providers. As a result, developers created "provider bundles" — users install large Providers containing a huge number of preselected sources.

But this bundling is a pain for developers to manage and test, and it leaves users with unwanted sources (e.g. wrong language), broken links, and inconsistent reliability. Developers are doing great work, but the "bundle" design itself is at fault.

FireStream inverts that: you choose content from a single provider: TMDB, and instead of huge bundles of semi-working links, FireStream gives you **granular control** — you, the user, install only **Sources**, and only the few you actually need and want.

- **A Source is a single website.** Not a bundle, not a repository of unknowns — one site = one source.
- **Quality and speed are the goal.** The point of a source is that it works, and works fast. You curate a short list you trust rather than carrying a long list you don't.
- **You decide.** Nothing is bundled in by default. Your source list is exactly what you chose to install.


You want to create your own source ? Look at:
https://github.com/refirestream/FireTemplate
