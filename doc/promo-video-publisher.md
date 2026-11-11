# Promotional Video Publisher guide

Create evidence-based promotional material for mobile apps, websites, desktop software, developer tools and services. English is the default for marketing copy and reusable artifacts; user communication follows the user's language.

## Entry points

| Resource | Responsibility |
| --- | --- |
| [Skill](../.agents/skills/promo-video-publisher/SKILL.md) | Scope, language, authorization, mode routing and delivery |
| [Workflow](../.agents/workflows/publish-promo-video.md) | Repository entry and execution policy |
| [Request prompt](../.agents/prompts/publish-promo-video.md) | Optional inputs; reuse information already provided |
| [Strategy](../.agents/skills/promo-video-publisher/references/strategy.md) | Product evidence, audience, search research, story and CTA |
| [Production](../.agents/skills/promo-video-publisher/references/production.md) | Real recordings, editing, text, export and verification |
| [Music](../.agents/skills/promo-video-publisher/references/music.md) | Track selection, promotional rights, mixing and audible delivery |
| [Publishing](../.agents/skills/promo-video-publisher/references/publishing.md) | Account actions, two placements, recovery and destination checks |
| [Timeline](../.agents/skills/promo-video-publisher/assets/timeline-template.json) | Shared source/output timing and text styles |
| [Status record](../.agents/skills/promo-video-publisher/assets/publishing-status-template.md) | Evidence, pending work and safe resumption |

Platform references for [YouTube](../.agents/skills/promo-video-publisher/references/platforms/youtube.md), [TikTok](../.agents/skills/promo-video-publisher/references/platforms/tiktok.md), [Instagram](../.agents/skills/promo-video-publisher/references/platforms/instagram.md) and [Threads](../.agents/skills/promo-video-publisher/references/platforms/threads.md) load only when relevant. Current account capabilities must be verified during execution.

## Examples

Analyze only:
> Use promo-video-publisher to analyze this product and propose an English video concept, search phrases and a verified destination. Do not publish.

Create from a silent recording:
> Turn this existing recording into an English promotional video with concise timed explanations and a cover. I do not want to record narration. Keep the original and deliver a clean master and a version with text.

Focused edit:
> Update only the English ending card and cover using this campaign's saved assets. Keep the existing timing unless the text needs more reading time.

Publish:
> Publish the verified campaign video to these accounts using my specified phone. Include the prepared description and author comment with the product name and destination URL, then verify both.

Resume an existing campaign:
> Read the saved publishing status and finish the outstanding authorized items. Inspect the existing posts before making changes.

## Delivery behavior

The default description/comment package applies to TikTok, YouTube, Instagram and Threads; its exact execution and exceptions are maintained in the publishing reference. Threads uses a post body and an author reply under that post, with client-specific cover, music and link checks. The action may be downloading an app, visiting a website, reading documentation or another verified product-specific route. Profile identity remains distinct from a single product.

Narration-free recordings use authored explanations rather than invented speech transcripts, with suitable licensed background music by default. Preserve an explicit silent choice and meaningful source audio. Select and listen to an actual track, record its source and rights, and verify audible playback separately for each platform. Platform-library music is not automatically licensed for cross-platform reuse. AI narration is optional. The timeline template uses source and output timestamps so captions can follow cuts and speed changes; it is not a renderer. SRT/VTT carries timed text, while layout and style remain in the render/editor configuration.

A campaign archive normally lives under `docs/promo/<campaign>/`. Create only needed artifacts and preserve original sources. A completed upload is distinct from public visibility, correct cover/text display, applied music and audible playback, a saved comment, a reachable destination and analytics integration. Existing posts receive supported in-place audio edits; an unsupported edit does not authorize deleting or reuploading the post.

## DevLib integration

The reusable skill does not inherit DevLib's product, package name, device or permissions. Read the target project's execution policy. DevLib's default exclusion of device commands remains in force unless the user's current request authorizes the relevant operation. Editing or copying the skill alone does not authorize device control, posting or account changes.

Claude and Gemini continue using the root AGENTS.md bridge and the same repository-local workflow. The personal skill is a distribution copy; keep it synchronized when updating this package without overwriting unrelated personal files.

## Validation

Run the skill-creator quick validator, the repository Agent structure validator, and check relative links, template syntax and personal/repository copy consistency. These checks do not prove video quality or successful platform publishing. Verify those against actual media and account evidence during the corresponding task.
