#!/usr/bin/env python3
"""小爱音箱输入输出测试 - 使用 xiaoai.py 的实际代码"""

import asyncio
import logging
import time

from xiaoai import XiaoAiClient, setup_logging

logger = logging.getLogger("xiaoai-test")


async def test_config():
    logger.info("=" * 50)
    logger.info("1. 配置加载测试")
    logger.info("=" * 50)

    try:
        client = XiaoAiClient("config.yaml")
    except FileNotFoundError as e:
        logger.error(f"❌ 配置文件加载失败: {e}")
        return None, False

    ok = True
    errors = []

    checks = [
        ("broker_url", client.broker_url),
        ("token", "***" if client.token else None),
        ("subscribe_topic", client.subscribe_topic),
        ("publish_topic", client.publish_topic),
        ("mi_user", client.mi_user),
        ("device_id", client.device_id),
    ]

    for name, value in checks:
        if name == "token":
            logger.info(f"  {name}: {value}")
        elif value:
            logger.info(f"  {name}: {value}")
        else:
            logger.warning(f"  {name}: ❌ 未配置")
            errors.append(f"缺少 {name}")

    if errors:
        logger.warning(f"❌ 配置检查失败: {', '.join(errors)}")
        ok = False
    else:
        logger.info("✅ 配置检查通过")

    return client, ok


async def test_miservice(client):
    logger.info("=" * 50)
    logger.info("2. MiService 登录测试")
    logger.info("=" * 50)

    ok = await client._init_miservice()
    if ok:
        logger.info("✅ MiService 登录成功")
    else:
        logger.error("❌ MiService 登录失败")

    return ok


async def test_tts(client):
    logger.info("=" * 50)
    logger.info("3. TTS 输出测试")
    logger.info("=" * 50)

    test_text = f"你好，这是联调测试。当前时间 {time.strftime('%H点%M分')}"
    logger.info(f"测试文本: {test_text}")

    try:
        result = await client._mi_na.text_to_speech(client.device_id, test_text)
        if result.get("code") == 0:
            logger.info("✅ TTS 发送成功，请听音箱是否有声音")
            return True
        else:
            logger.error(f"❌ TTS 失败: {result}")
            return False
    except Exception as e:
        logger.error(f"❌ TTS 异常: {e}")
        return False


async def test_conversation(client):
    logger.info("=" * 50)
    logger.info("4. 对话输入测试（conversation API）")
    logger.info("=" * 50)

    try:
        records = await client._get_conversation_records()
        if not records:
            logger.info("⚠️  暂无对话记录（最近没有和小爱说过话）")
            logger.info("💡 现在可以对音箱说句话，然后重新运行此测试")
            return True

        logger.info(f"✅ 获取到 {len(records)} 条对话记录:")
        for i, r in enumerate(records):
            query = r.get("query", "") or r.get("transcription", "")
            time_str = time.strftime("%H:%M:%S", time.localtime(r.get("time", 0) / 1000))
            logger.info(f"  [{i+1}] {time_str} - {query[:50]}")

        return True

    except Exception as e:
        logger.error(f"❌ 对话测试异常: {e}")
        import traceback

        logger.debug(traceback.format_exc())
        return False


async def main():
    setup_logging("INFO")

    client, config_ok = await test_config()
    if not config_ok:
        logger.warning("配置不完整，跳过后续测试")
        return

    results = {"配置加载": True}

    miservice_ok = await test_miservice(client)
    results["MiService登录"] = miservice_ok

    if not miservice_ok:
        logger.warning("登录失败，跳过后续测试")
    else:
        tts_ok = await test_tts(client)
        results["TTS输出"] = tts_ok

        conv_ok = await test_conversation(client)
        results["对话输入"] = conv_ok

    # 清理资源
    if client and client._session:
        await client._session.close()

    logger.info("=" * 50)
    logger.info("测试总结")
    logger.info("=" * 50)
    for name, ok in results.items():
        logger.info(f"  {name}: {'✅' if ok else '❌'}")

    if all(results.values()):
        logger.info("🎉 全部测试通过！")
    else:
        logger.warning("部分测试未通过，请检查上方日志")


if __name__ == "__main__":
    asyncio.run(main())
