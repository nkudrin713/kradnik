"""Offline regression tests for the Threads extractor.

These use synthetic page HTML — no network — so they run in CI and pin the
behaviours that are easy to regress, above all: never return a different
post's video than the one that was requested.
"""

import base64
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from yt_dlp import YoutubeDL  # noqa: E402
from yt_dlp.utils import ExtractorError  # noqa: E402

from yt_dlp_plugins.extractor.threads import ThreadsIE  # noqa: E402


def _efg(duration_s, width=720):
    """Build a CDN-style base64 `efg` param (where duration actually lives)."""
    blob = json.dumps({
        'vencode_tag': f'xpv_progressive.INSTAGRAM.FEED.C3.{width}.dash_baseline_1_v1',
        'duration_s': duration_s,
    }).encode()
    return base64.urlsafe_b64encode(blob).decode().rstrip('=')


def _video_url(name, duration_s):
    return f'https://scontent.cdninstagram.com/o1/v/{name}.mp4?efg={_efg(duration_s)}&oe=1'


def _post(code, username, *, videos=(), carousel=None, has_audio=True,
          media_type=2, caption='hello world'):
    post = {
        'code': code,
        'pk': '123',
        'user': {'username': username, 'full_name': username.title()},
        'caption': {'text': caption} if caption else None,
        'taken_at': 1782587654,
        'like_count': 42,
        'original_width': 1296,
        'original_height': 720,
        'media_type': media_type,
        'has_audio': has_audio,
        'image_versions2': {'candidates': [
            {'url': 'https://scontent.cdninstagram.com/thumb_small.jpg', 'width': 150, 'height': 150},
            {'url': 'https://scontent.cdninstagram.com/thumb_big.jpg', 'width': 640, 'height': 640},
        ]},
    }
    if videos:
        post['video_versions'] = [
            {'type': 101 + i, 'url': u, 'width': None, 'height': None}
            for i, u in enumerate(videos)
        ]
    if carousel is not None:
        post['carousel_media'] = carousel
    return post


def _page(posts, og_url=None):
    """Wrap posts in the nested shape Threads actually ships, plus noise."""
    thread = {'node': {'thread_items': [{'post': p} for p in posts]}}
    result = {'data': {'data': {'edges': [thread]}}}
    payload = {'require': [['ScheduledServerJS', 'handle', None,
                            [{'__bbox': {'result': result}}]]]}
    head = f'<meta property="og:url" content="{og_url}" />' if og_url else ''
    return (
        f'<html><head>{head}</head><body>'
        '<script type="application/json" data-sjs>{"not":"a post"}</script>'
        '<script type="application/json" data-sjs>NOT VALID JSON AT ALL</script>'
        f'<script type="application/json" data-sjs>{json.dumps(payload)}</script>'
        '</body></html>'
    )


def _extract(html, url):
    ie = ThreadsIE(YoutubeDL({'quiet': True, 'simulate': True}))
    ie._download_webpage = lambda *args, **kwargs: html  # noqa: SLF001
    return ie._real_extract(url)  # noqa: SLF001


class TestThreadsExtractor(unittest.TestCase):
    def test_unresolved_share_never_returns_another_posts_video(self):
        html = _page([_post('UNRELATED', 'stranger', videos=[_video_url('wrong', 5)])])
        with self.assertRaises(ExtractorError):
            _extract(html, 'https://www.threads.com/share/UNRESOLVED')

    def test_mixed_carousel_keeps_video_order_and_canonical_post(self):
        items = [
            _post('PHOTO1', 'creator', media_type=1),
            _post('VIDEO2', 'creator', videos=[_video_url('second', 2)]),
            _post('PHOTO3', 'creator', media_type=1),
            _post('VIDEO4', 'creator', videos=[_video_url('fourth', 4)]),
        ]
        info = _extract(_page([_post('MIXED123', 'creator', carousel=items, media_type=8)]),
                        'https://www.threads.com/@creator/post/MIXED123')
        self.assertEqual([v['id'] for v in info['entries']], ['MIXED123_2', 'MIXED123_4'])
        self.assertEqual(info['webpage_url'], 'https://www.threads.com/t/MIXED123/')

    def test_share_with_one_video_keeps_the_root_post_identity(self):
        items = [_post('PHOTO1', 'creator', media_type=1),
                 _post('VIDEO2', 'creator', videos=[_video_url('second', 2)])]
        info = _extract(_page([_post('MIXED123', 'creator', carousel=items, media_type=8)],
                              og_url='https://www.threads.com/@creator/post/MIXED123'),
                        'https://www.threads.com/share/SHARED')
        self.assertEqual(info['id'], 'MIXED123_2')
        self.assertEqual(info['webpage_url'], 'https://www.threads.com/t/MIXED123/')

    def test_selects_requested_post_not_first_on_page(self):
        """The requested post wins even when another video precedes it.

        This is the regression guard for the worst failure mode: a Threads page
        embeds many posts, and taking the first video returns a stranger's clip.
        """
        html = _page([
            _post('OTHERPOST1', 'someone_else', videos=[_video_url('wrong', 55)]),
            _post('TARGETPOST', 'kfury', videos=[_video_url('right', 11)]),
        ])
        info = _extract(html, 'https://www.threads.com/@kfury/post/TARGETPOST')
        self.assertEqual(info['id'], 'TARGETPOST')
        self.assertEqual(info['uploader_id'], 'kfury')
        self.assertEqual(info['duration'], 11)
        self.assertIn('right.mp4', info['formats'][0]['url'])

    def test_missing_post_raises_instead_of_returning_another(self):
        """A deleted/gated post must error, never fall back to a random video."""
        html = _page([_post('SOMEONEELSE', 'stranger', videos=[_video_url('wrong', 55)])])
        with self.assertRaises(ExtractorError) as ctx:
            _extract(html, 'https://www.threads.com/@zuck/post/GONE12345')
        self.assertIn('GONE12345', str(ctx.exception))

    def test_share_link_resolves_via_og_url(self):
        html = _page(
            [_post('REALCODE99', 'hunter', videos=[_video_url('shared', 36)])],
            og_url='https://www.threads.com/@hunter/post/REALCODE99')
        info = _extract(html, 'https://www.threads.com/share/_abc123/')
        self.assertEqual(info['id'], 'REALCODE99')
        self.assertEqual(info['duration'], 36)

    def test_image_only_post_raises_clean_error(self):
        html = _page([_post('IMGPOST123', 'photog', media_type=1, caption='a photo')])
        with self.assertRaises(ExtractorError) as ctx:
            _extract(html, 'https://www.threads.com/@photog/post/IMGPOST123')
        self.assertIn('image post', str(ctx.exception))

    def test_carousel_returns_multi_video_playlist(self):
        carousel = [
            _post('C1', 'creator', videos=[_video_url('part1', 5)]),
            _post('C2', 'creator', videos=[_video_url('part2', 7)]),
        ]
        html = _page([_post('CAROUSEL01', 'creator', carousel=carousel, media_type=8)])
        info = _extract(html, 'https://www.threads.com/@creator/post/CAROUSEL01')
        self.assertEqual(info['_type'], 'multi_video')
        entries = list(info['entries'])
        self.assertEqual(len(entries), 2)
        self.assertEqual(entries[0]['id'], 'CAROUSEL01_1')
        self.assertIn('part2.mp4', entries[1]['formats'][0]['url'])

    def test_silent_video_marked_acodec_none(self):
        html = _page([_post('SILENT0001', 'nosound',
                            videos=[_video_url('quiet', 9)], has_audio=False)])
        info = _extract(html, 'https://www.threads.com/@nosound/post/SILENT0001')
        self.assertEqual(info['formats'][0]['acodec'], 'none')

    def test_duplicate_renditions_are_deduped(self):
        """type 101/102/103 are usually the same asset re-signed."""
        same = 'https://scontent.cdninstagram.com/o1/v/same.mp4'
        post = _post('DEDUP00001', 'user', videos=[f'{same}?a=1', f'{same}?a=2'])
        info = _extract(_page([post]), 'https://www.threads.com/@user/post/DEDUP00001')
        self.assertEqual(len(info['formats']), 1)

    def test_metadata_and_thumbnails(self):
        html = _page([_post('META000001', 'kfury', videos=[_video_url('v', 11)])])
        info = _extract(html, 'https://www.threads.com/@kfury/post/META000001')
        self.assertEqual(info['uploader_url'], 'https://www.threads.com/@kfury')
        self.assertEqual(info['timestamp'], 1782587654)
        self.assertEqual(info['like_count'], 42)
        self.assertEqual(info['title'], 'hello world')
        self.assertEqual(len(info['thumbnails']), 2)

    def test_efg_decoding_is_resilient(self):
        self.assertEqual(ThreadsIE._efg_info('https://x/y.mp4'), {})
        self.assertEqual(ThreadsIE._efg_info('https://x/y.mp4?efg=!!!notbase64!!!'), {})

    def test_valid_url_matching(self):
        for url in (
            'https://www.threads.com/@user/post/ABC123',
            'https://www.threads.net/@user/post/ABC123',
            'https://threads.com/@user/post/ABC123',
            'https://www.threads.com/t/ABC123',
            'https://www.threads.com/share/ABC123/',
            'https://www.threads.com/@user/post/ABC123?xmt=tracking&slof=1',
        ):
            self.assertTrue(ThreadsIE.suitable(url), url)
        for url in ('https://www.threads.com/@user', 'https://example.com/@u/post/X'):
            self.assertFalse(ThreadsIE.suitable(url), url)


if __name__ == '__main__':
    unittest.main()
