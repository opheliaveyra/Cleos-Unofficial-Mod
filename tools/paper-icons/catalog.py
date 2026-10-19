"""Original 24-unit paper drawings. Each path is independent, never atlas crops.
Tuple: SVG path, fill token (paper/lilac/blue/none), pencil outline.
"""
ICONS = {
'chat': [('M4 4.5 Q3 4.5 3 6 L3.3 16 Q3.4 17.5 5 17.5 L7 17.5 L6.5 21 L11 17.5 L19 17.5 Q21 17.5 21 15.5 L20.7 6 Q20.7 4 19 4 Z','paper',True),('M6 7 Q12 5.7 18 7 L18 12 Q12 13 6 12 Z','lilac',False),('M7 9 L16 9 M7 12 L14 12','none',True)],
'diary': [('M5 3.5 L18 3 Q20 3 20 5 L20 20 L5 20.5 Q3 20.5 3 18.5 L3.4 6 Q3.4 3.5 5 3.5 Z','paper',True),('M5 4 L7 4 L6.7 20 L5 20 Z','lilac',False),('M10 7 L17 7 M10 10 L16 10 M10 13 L17 13 M4 17.5 L19 17','none',True),('M15 3.5 L15 6 L17 5 L18.5 6 L18.5 3.5 Z','blue',True)],
'todo': [('M6 4 L18 4 Q20 4 20 6 L19.5 21 L4 20.5 L4.5 6 Q4.5 4 6 4 Z','paper',True),('M9 3 L15 3 L15 6 L9 6 Z','lilac',True),('M7 10 L8.5 11.5 L11 8.5 M13 10 L17 10 M7 16 L8.5 17.5 L11 14 M13 16 L17 16','none',True)],
'home': [('M4 10 L12 3 L21 10 L19 10 L19 20 L5 20 L5 10 Z','paper',True),('M3 10 L12 2.8 L21 10 L19.5 11 L12 5 L4.5 11 Z','lilac',True),('M10 20 L10 14 L14 14 L14 20 Z','blue',True),('M7 12 L8 12','none',True)],
'role': [('M4 21 Q4 14 12 14 Q20 14 20 21 Z','lilac',True),('M8 8 Q7 3 12 3 Q17 3 16 8 Q16 12 12 12 Q8 12 8 8 Z','paper',True),('M8 7 Q12 7 13 4 M10 17 L12 19 L14 17','none',True)],
'group': [('M2 20 Q2 14 8 14 Q14 14 14 20 Z','blue',True),('M15 14 Q22 14 22 20 L17 20','lilac',True),('M5 7 Q5 3 8 3 Q12 3 11 7 Q11 11 8 11 Q5 11 5 7 Z','paper',True),('M15 4 Q20 3 20 7 Q20 11 16 11','paper',True)],
'game': [('M7 7 L17 7 Q20 7 21 12 L22 17 Q22 20 19 19 L15 16 L9 16 L5 19 Q2 20 2.5 16 L3.5 11 Q4 7 7 7 Z','paper',True),('M5 9 Q9 7 11 10 L10 14 L5 14 Z','lilac',False),('M7 10 L7 14 M5 12 L9 12 M16 11 L16.1 11 M18 13 L18.1 13','none',True)],
'letter': [('M3 7 L21 7 L20.5 20 L3.5 20 Z','paper',True),('M3 7 L12 2.5 L21 7 L12 14 Z','lilac',True),('M4 19 L9 14 M20 19 L15 14','none',True)],
'memory': [('M5 3.5 L18 3.5 L20 6 L19 21 L4 20 L4 6 Z','paper',True),('M5 4 L8 4 L8 11 L6.5 10 L5 11 Z','lilac',True),('M11 9 Q17 7 17 12 Q17 15 13 15 L11 18 L11 15 L10 13 Q8 11 11 9 Z','blue',True)],
'bookmark': [('M6 3.5 L18 3 L18 21 L12 17 L6 21 Z','paper',True),('M8 5 L16 5 L16 12 Q12 13 8 12 Z','lilac',False),('M10 7 L14 7 M10 10 L13 10','none',True)],
'favorite': [('M12 20 Q1 13 3 7 Q5 2 12 7 Q18 1 21 7 Q23 13 12 20 Z','lilac',True),('M6 7 Q8 5 10 8','none',True)],
'settings': [('M9 3 L15 3 L16 6 L19 7 L21 12 L19 17 L16 18 L15 21 L9 21 L8 18 L5 17 L3 12 L5 7 L8 6 Z','paper',True),('M8 12 C8 7 16 7 16 12 C16 17 8 17 8 12 Z','lilac',True),('M10 4 L13 4 M5 11 L5 13','none',True)],
'model': [('M5 5 L19 5 L19 19 L5 19 Z','paper',True),('M8 8 L16 8 L16 16 L8 16 Z','lilac',True),('M8 2 L8 5 M12 2 L12 5 M16 2 L16 5 M8 19 L8 22 M12 19 L12 22 M16 19 L16 22 M2 8 L5 8 M2 16 L5 16 M19 8 L22 8 M19 16 L22 16','none',True)],
'file': [('M5 3 L14 3 L20 9 L19 21 L5 21 Z','paper',True),('M14 3 L14 9 L20 9 Z','blue',True),('M8 12 L16 12 M8 15 L16 15 M8 18 L13 18','none',True)],
'folder': [('M3 6 L9 6 L11 9 L21 9 L20 20 L3 20 Z','lilac',True),('M4 10 L21 10 L19.5 20 L3 20 Z','paper',True),('M6 13 L16 13','none',True)],
'book': [('M3 4 Q8 2 12 5 Q16 2 21 4 L20.5 20 Q16 18 12 21 Q8 18 3.5 20 Z','paper',True),('M4 5 Q9 4 11 6 L11 17 Q8 16 4 17 Z','blue',False),('M12 5 L12 21 M15 7 L18 6.5 M15 10 L18 9.5 M6 8 L9 9 M6 11 L9 12','none',True)],
'image': [('M3 4 L21 4.5 L20 20 L3.5 20 Z','paper',True),('M5 17 L9 11 L13 15 L16 10 L19 17 Z','blue',True),('M7 8 C7 5.5 11 5.5 11 8 C11 10.5 7 10.5 7 8 Z','lilac',True)],
'music': [('M9 17 L9 5 L20 3 L20 15','none',True),('M9 5 L20 3 L20 7 L9 9 Z','lilac',True),('M9 17 C9 13 2 15 3 19 C4 22 9 20 9 17 Z','blue',True),('M20 15 C20 11 13 13 14 17 C15 20 20 18 20 15 Z','paper',True)],
'calendar': [('M4 5 L20 5 L20 21 L4 20 Z','paper',True),('M4 5 L20 5 L20 10 L4 10 Z','lilac',True),('M8 3 L8 7 M16 3 L16 7 M8 13 L10 13 M14 13 L16 13 M8 17 L10 17 M14 17 L16 17','none',True)],
'service': [('M4 4 L10 4 Q9 1 12 2 Q15 2 14 5 L20 5 L20 11 Q23 10 22 13 Q22 16 19 15 L19 21 L13 21 Q14 18 11 18 Q8 18 9 21 L3 20 L3 14 Q6 15 6 12 Q6 9 3 10 Z','paper',True),('M5 6 L9 6 L9 9 L5 9 Z','blue',False)],
'camera': [('M3 7 L8 7 L9 4 L15 4 L17 7 L21 7 L21 20 L3 20 Z','paper',True),('M8 13 C8 7.5 17 7.5 17 13 C17 18.5 8 18.5 8 13 Z','blue',True),('M5 9 L6 9','none',True)],
'mic': [('M9 6 Q9 2 12 2 Q15 2 15 6 L15 12 Q15 16 12 16 Q9 16 9 12 Z','lilac',True),('M6 11 Q6 19 12 19 Q18 19 18 11 M12 19 L12 22 M9 22 L15 22 M10 6 L13 6 M10 9 L13 9','none',True)],
'attachment': [('M9 16 L17 8 Q20 5 17 3 Q15 1 12 4 L4 12 Q0 16 4 20 Q8 24 12 20 L21 11 M7 15 L15 7 Q17 5 18 7 Q19 8 17 10 L9 18 Q6 20 5 17','none',True)],
'send': [('M3 4 L22 12 L3 21 L6 13 Z','paper',True),('M6 13 L22 12 L10 16 L9 20 Z','blue',True)],
'copy': [('M3 3 L16 3 L16 17 L3 17 Z','lilac',True),('M8 7 L21 7 L21 21 L8 21 Z','paper',True),('M11 11 L18 11 M11 14 L18 14 M11 17 L16 17','none',True)],
'edit': [('M4 16 L15 4 L20 9 L9 20 L3 21 Z','paper',True),('M15 4 L17 2 L22 7 L20 9 Z','lilac',True),('M4 16 L9 20 M7 16 L16 7','none',True)],
'voice': [('M4 10 L8 10 L14 5 L14 20 L8 15 L4 15 Z','paper',True),('M17 9 Q21 12 17 16 M19 6 Q25 12 19 19','none',True)],
'cloud': [('M6 18 Q1 17 3 12 Q4 9 8 10 Q7 4 13 4 Q18 4 18 10 Q22 9 22 14 Q22 18 18 18 Z','blue',True),('M7 14 Q12 12 17 14','none',True)],
'alarm': [('M5 12 C5 3 19 3 19 12 C19 21 5 21 5 12 Z','paper',True),('M12 8 L12 12 L16 14 M4 5 L7 3 M18 3 L21 5 M7 20 L5 22 M17 20 L19 22','none',True)],
'location': [('M12 22 Q3 12 5 7 Q7 1 13 3 Q23 5 18 14 Z','paper',True),('M9 9 C9 5 16 5 16 9 C16 13 9 13 9 9 Z','lilac',True)],
'palette': [('M12 3 C1 3 0 16 7 20 Q13 23 13 18 Q12 15 17 16 Q23 17 22 11 Q21 3 12 3 Z','paper',True),('M6 9 L8 9 M11 6 L13 6 M17 8 L18 9 M5 14 L7 15','none',True)],
'feed': [('M4 3 L20 3 L20 21 L4 21 Z','paper',True),('M7 6 L17 6 L17 12 L7 12 Z','blue',True),('M7 15 L17 15 M7 18 L14 18','none',True)],
'spark': [('M12 3 L14 9 L20 12 L14 14 L12 21 L10 14 L3 12 L10 9 Z','lilac',True),('M19 3 L19 7 M17 5 L21 5','none',True)],
'box': [('M3 7 L12 3 L21 7 L20 20 L4 20 Z','paper',True),('M3 7 L21 7 L21 11 L3 11 Z','lilac',True),('M9 14 L15 14','none',True)],
'world': [('M3 12 C3 0 21 0 21 12 C21 24 3 24 3 12 Z','paper',True),('M12 3 Q5 12 12 21 Q19 12 12 3 Z','blue',True),('M3 12 L21 12 M5 7 L19 7 M5 17 L19 17','none',True)],
'widget': [('M3 3 L10 3 L10 10 L3 10 Z M14 3 L21 3 L21 10 L14 10 Z M3 14 L10 14 L10 21 L3 21 Z','paper',True),('M14 14 L21 14 L21 21 L14 21 Z','lilac',True)],
}
ALIASES = {
'chat':'ChatBubble ChatBubbleOutline Forum AddComment MarkChatRead', 'diary':'AutoStories EditNote',
'todo':'TaskAlt Checklist', 'home':'Home', 'role':'Person PersonAdd Face', 'group':'Groups',
'game':'SportsEsports GridOn', 'letter':'Mail MailOutline MarkEmailUnread',
'memory':'Psychology', 'bookmark':'Bookmark BookmarkBorder', 'favorite':'Favorite FavoriteBorder',
'settings':'Settings', 'model':'Memory', 'file':'Description', 'folder':'Folder', 'book':'Book MenuBook',
'image':'Image AddPhotoAlternate', 'music':'MusicNote', 'calendar':'CalendarMonth Event',
'service':'Extension', 'camera':'PhotoCamera', 'mic':'Mic', 'attachment':'AttachFile', 'send':'Send',
'copy':'ContentCopy', 'edit':'Edit', 'voice':'GraphicEq VolumeUp', 'cloud':'Cloud', 'alarm':'Alarm Timer',
'location':'LocationOn', 'palette':'Palette', 'feed':'DynamicFeed', 'spark':'AutoAwesome Lightbulb',
'box':'Inventory2', 'world':'TravelExplore Explore', 'widget':'Widgets',
}
LABELS = dict(zip(ICONS, ['聊天','日记','待办','主页','角色','群聊','游戏','信件','记忆','收藏书签','喜欢','设置','模型','文件','文件夹','世界书','图库','音乐','日历','外部服务','相机','语音输入','附件','发送','复制','编辑','声音','天气','闹钟/计时器','位置','外观','动态','灵感','数据备份','联网/探索','组件']))
COLORS = {'paper':'#FFF9EF','lilac':'#DED4F1','blue':'#C9DEEA','none':'none'}
# State-bearing variants must never collapse to the same drawing.
ICONS['favorite_outline'] = [(ICONS['favorite'][0][0], 'paper', True), ICONS['favorite'][1]]
ICONS['unread_letter'] = ICONS['letter'] + [('M16 5 C16 1 22 1 22 5 C22 9 16 9 16 5 Z','blue',True)]
ICONS['read_chat'] = ICONS['chat'] + [('M13 16 L16 19 L22 13','none',True)]
ICONS['add_role'] = ICONS['role'] + [('M17 8 L22 8 M19.5 5.5 L19.5 10.5','none',True)]
ICONS['add_image'] = ICONS['image'] + [('M17 5 L22 5 M19.5 2.5 L19.5 7.5','none',True)]
ALIASES['favorite'] = 'Favorite'
ALIASES['favorite_outline'] = 'FavoriteBorder'
ALIASES['letter'] = 'Mail MailOutline'
ALIASES['unread_letter'] = 'MarkEmailUnread'
ALIASES['chat'] = 'ChatBubble ChatBubbleOutline Forum AddComment'
ALIASES['read_chat'] = 'MarkChatRead'
ALIASES['role'] = 'Person Face'
ALIASES['add_role'] = 'PersonAdd'
ALIASES['image'] = 'Image'
ALIASES['add_image'] = 'AddPhotoAlternate'
LABELS.update(favorite_outline='尚未喜欢', unread_letter='未读信件', read_chat='已读聊天', add_role='添加角色', add_image='添加图片')
