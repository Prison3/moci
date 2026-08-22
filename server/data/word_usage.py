"""为小学词汇生成常用短语、例句及其中文翻译。"""

from __future__ import annotations

import re

VOWELS = set("aeiou")

UNCOUNTABLE = {
    "air", "beef", "bread", "butter", "candy", "chicken", "chocolate",
    "earth", "english", "fire", "fish", "football", "grass", "hair",
    "homework", "housework", "ice", "ice cream", "internet", "juice",
    "math", "maths", "meat", "milk", "music", "paper", "pe", "people",
    "pork", "rice", "science", "snow", "soup", "space", "tea", "water",
    "weather", "work",
}

VERBS = {
    "am", "are", "arrive", "ask", "be", "begin", "buy", "call", "can",
    "catch", "close", "come", "cook", "dance", "do", "draw", "drink",
    "drive", "eat", "exercise", "feel", "find", "finish", "fly", "get",
    "give", "go", "has", "have", "hear", "help", "hope", "hurry", "hurt",
    "is", "jump", "keep", "know", "learn", "let", "like", "listen", "live",
    "look", "love", "make", "meet", "move", "must", "need", "open", "play",
    "put", "read", "ride", "run", "say", "see", "sell", "share", "show",
    "sing", "sit", "sleep", "speak", "stand", "start", "stop", "study",
    "swim", "take", "talk", "teach", "tell", "think", "travel", "try",
    "turn", "use", "visit", "wait", "wake", "walk", "want", "wash", "watch",
    "wear", "will", "work", "worry", "write",
}

# phrase, example（英文）；中文由 translate_usage / usage_for 统一生成
SPECIAL: dict[str, tuple[str, str]] = {
    "i": ("I am", "I am a student."),
    "you": ("thank you", "How are you?"),
    "he": ("he is", "He is my friend."),
    "she": ("she is", "She is my sister."),
    "it": ("it is", "It is a cat."),
    "we": ("we are", "We are happy."),
    "they": ("they are", "They are my classmates."),
    "me": ("help me", "Can you help me?"),
    "him": ("with him", "I play with him."),
    "her": ("her book", "This is her book."),
    "us": ("with us", "Come with us."),
    "them": ("with them", "I like them."),
    "my": ("my name", "My name is Tom."),
    "your": ("your book", "Is this your book?"),
    "his": ("his bag", "This is his bag."),
    "its": ("its name", "What is its name?"),
    "our": ("our school", "This is our school."),
    "their": ("their teacher", "Their teacher is kind."),
    "mine": ("it is mine", "This book is mine."),
    "yours": ("it is yours", "Is this yours?"),
    "this": ("this is", "This is a pen."),
    "that": ("that is", "That is my desk."),
    "these": ("these are", "These are my books."),
    "those": ("those are", "Those are apples."),
    "who": ("who is", "Who is she?"),
    "whose": ("whose book", "Whose book is this?"),
    "what": ("what is", "What is this?"),
    "which": ("which one", "Which one do you like?"),
    "where": ("where is", "Where is my bag?"),
    "when": ("when is", "When is your birthday?"),
    "why": ("why not", "Why are you happy?"),
    "how": ("how are you", "How are you today?"),
    "a": ("a book", "I have a book."),
    "an": ("an apple", "I eat an apple."),
    "the": ("the sun", "The sun is bright."),
    "and": ("you and I", "Tom and I are friends."),
    "or": ("tea or juice", "Do you like tea or juice?"),
    "but": ("but I like", "I am tired, but I am happy."),
    "not": ("do not", "I do not know."),
    "no": ("no thanks", "No, thank you."),
    "yes": ("yes, I do", "Yes, I like it."),
    "ok": ("OK, let's go", "OK, let's go."),
    "please": ("sit down, please", "Sit down, please."),
    "sorry": ("I am sorry", "I am sorry."),
    "thank": ("thank you", "Thank you very much."),
    "thanks": ("many thanks", "Thanks a lot."),
    "hello": ("hello, everyone", "Hello, my name is Amy."),
    "hi": ("hi, Tom", "Hi, how are you?"),
    "goodbye": ("say goodbye", "Goodbye, Miss White."),
    "bye": ("bye-bye", "Bye, see you tomorrow."),
    "welcome": ("welcome to", "Welcome to our school."),
    "pardon": ("pardon me", "Pardon? Please say it again."),
    "in": ("in the box", "The book is in the bag."),
    "on": ("on the desk", "The pen is on the desk."),
    "at": ("at school", "I am at school."),
    "to": ("go to school", "I go to school."),
    "from": ("from China", "I am from China."),
    "for": ("for you", "This gift is for you."),
    "of": ("a cup of", "I want a cup of tea."),
    "with": ("with me", "Come with me."),
    "about": ("about ten", "The book is about animals."),
    "under": ("under the desk", "The cat is under the desk."),
    "behind": ("behind the door", "The bag is behind the door."),
    "beside": ("beside me", "Sit beside me."),
    "between": ("between A and B", "The park is between the school and the shop."),
    "near": ("near the school", "My home is near the school."),
    "over": ("over there", "Look over there."),
    "up": ("stand up", "Please stand up."),
    "down": ("sit down", "Please sit down."),
    "off": ("take off", "Take off your coat."),
    "out": ("go out", "Let's go out."),
    "into": ("into the room", "Come into the room."),
    "after": ("after school", "I play after school."),
    "before": ("before class", "Wash your hands before lunch."),
    "again": ("try again", "Please say it again."),
    "also": ("I also like", "I also like apples."),
    "always": ("always happy", "She is always kind."),
    "sometimes": ("sometimes play", "I sometimes read at night."),
    "never": ("never late", "I am never late."),
    "often": ("often play", "We often play football."),
    "too": ("me too", "I like it, too."),
    "very": ("very good", "It is very nice."),
    "now": ("right now", "Let's go now."),
    "then": ("and then", "First read, then write."),
    "here": ("come here", "Come here, please."),
    "there": ("over there", "Look, there is a bird."),
    "today": ("today is", "Today is Monday."),
    "tomorrow": ("see you tomorrow", "See you tomorrow."),
    "yesterday": ("yesterday morning", "I played yesterday."),
    "all": ("all of us", "We are all here."),
    "some": ("some water", "I want some milk."),
    "any": ("any questions", "Do you have any questions?"),
    "many": ("many books", "I have many books."),
    "much": ("too much", "How much is it?"),
    "lot": ("a lot of", "I have a lot of friends."),
    "little": ("a little", "I am a little tired."),
    "family": ("my family", "I love my family."),
    "father": ("my father", "This is my father."),
    "dad": ("my dad", "My dad is a doctor."),
    "mother": ("my mother", "This is my mother."),
    "mum": ("my mum", "My mum is a teacher."),
    "mom": ("my mom", "My mom is kind."),
    "parent": ("my parent", "My parent is at home."),
    "brother": ("my brother", "I have a brother."),
    "sister": ("my sister", "This is my sister."),
    "grandfather": ("my grandfather", "My grandfather is old."),
    "grandpa": ("my grandpa", "I love my grandpa."),
    "grandmother": ("my grandmother", "My grandmother cooks well."),
    "grandma": ("my grandma", "My grandma is kind."),
    "uncle": ("my uncle", "This is my uncle."),
    "aunt": ("my aunt", "This is my aunt."),
    "cousin": ("my cousin", "My cousin is ten."),
    "baby": ("a baby", "The baby is cute."),
    "boy": ("a boy", "He is a boy."),
    "girl": ("a girl", "She is a girl."),
    "man": ("a man", "That man is a teacher."),
    "woman": ("a woman", "The woman is my mum."),
    "kid": ("a kid", "The kid is happy."),
    "people": ("many people", "There are many people in the park."),
    "friend": ("my friend", "He is my friend."),
    "mr": ("Mr Green", "Mr Green is our teacher."),
    "mrs": ("Mrs White", "Mrs White is kind."),
    "miss": ("Miss Li", "Miss Li teaches English."),
    "ms": ("Ms Wang", "Ms Wang is my aunt."),
    "am": ("I am", "I am a pupil."),
    "is": ("he is", "She is my sister."),
    "are": ("we are", "We are students."),
    "be": ("be happy", "Be a good boy."),
    "can": ("I can", "I can swim."),
    "will": ("I will", "I will help you."),
    "should": ("you should", "You should go to bed."),
    "must": ("you must", "You must wash your hands."),
    "let": ("let's go", "Let's play together."),
    "have": ("have a look", "I have a new book."),
    "has": ("he has", "She has a cat."),
    "do": ("do homework", "I do my homework."),
    "go": ("go home", "Let's go home."),
    "come": ("come in", "Come in, please."),
    "come on": ("come on", "Come on, you can do it!"),
    "look": ("look at", "Look at the picture."),
    "listen": ("listen to", "Listen to the teacher."),
    "play": ("play football", "I play football after school."),
    "like": ("like apples", "I like apples."),
    "love": ("love you", "I love my mum."),
    "want": ("want to", "I want to eat."),
    "need": ("need help", "I need your help."),
    "know": ("I know", "I know the answer."),
    "think": ("I think", "I think it is good."),
    "see": ("I see", "I see a bird."),
    "watch": ("watch TV", "I watch TV at night."),
    "hear": ("hear me", "Can you hear me?"),
    "find": ("find it", "I cannot find my pen."),
    "get": ("get up", "I get up at seven."),
    "give": ("give me", "Please give me a book."),
    "take": ("take a bus", "I take a bus to school."),
    "put": ("put on", "Put on your coat."),
    "make": ("make a cake", "Mum can make a cake."),
    "eat": ("eat an apple", "I eat an apple every day."),
    "drink": ("drink water", "Please drink some water."),
    "read": ("read a book", "I read a book."),
    "write": ("write a word", "Please write your name."),
    "speak": ("speak English", "I can speak English."),
    "talk": ("talk to", "Let's talk to Miss Li."),
    "tell": ("tell me", "Please tell me a story."),
    "say": ("say hello", "Say hello to your friends."),
    "ask": ("ask a question", "May I ask a question?"),
    "answer": ("answer the question", "Please answer the question."),
    "study": ("study English", "I study English every day."),
    "learn": ("learn English", "I learn English at school."),
    "teach": ("teach me", "Miss Li teaches us English."),
    "help": ("help me", "Can you help me?"),
    "work": ("go to work", "My dad goes to work."),
    "swim": ("go swimming", "I can swim."),
    "run": ("run fast", "I can run fast."),
    "jump": ("jump high", "The frog can jump."),
    "walk": ("walk to school", "I walk to school."),
    "dance": ("dance well", "She can dance well."),
    "sing": ("sing a song", "Let's sing a song."),
    "draw": ("draw a picture", "I can draw a cat."),
    "open": ("open the door", "Please open the door."),
    "close": ("close the window", "Please close the window."),
    "sit": ("sit down", "Sit down, please."),
    "stand": ("stand up", "Please stand up."),
    "sleep": ("go to sleep", "I sleep at nine."),
    "wake": ("wake up", "I wake up at seven."),
    "buy": ("buy a book", "I want to buy a book."),
    "boil": ("a boil", "He has a boil on his arm."),
    "sell": ("sell fruit", "They sell apples."),
    "use": ("use a pen", "I use a pencil."),
    "try": ("try again", "Please try again."),
    "keep": ("keep quiet", "Please keep quiet."),
    "live": ("live in", "I live in Beijing."),
    "move": ("move on", "Please move your chair."),
    "meet": ("nice to meet you", "Nice to meet you."),
    "call": ("call me", "Please call me tomorrow."),
    "show": ("show me", "Show me your book."),
    "share": ("share with", "Let's share the cake."),
    "catch": ("catch a ball", "I can catch the ball."),
    "fly": ("fly a kite", "I fly a kite in the park."),
    "ride": ("ride a bike", "I ride a bike to school."),
    "drive": ("drive a car", "My dad can drive a car."),
    "hurt": ("hurt my leg", "My leg hurts."),
    "feel": ("feel happy", "I feel happy today."),
    "hope": ("I hope", "I hope you are well."),
    "worry": ("don't worry", "Don't worry."),
    "hurry": ("hurry up", "Hurry up, please."),
    "exercise": ("do exercise", "I do exercise every morning."),
    "visit": ("visit my grandma", "I visit my grandma on Sunday."),
    "travel": ("travel by train", "We travel by train."),
    "wait": ("wait for", "Please wait for me."),
    "stop": ("stop talking", "Please stop."),
    "start": ("start class", "Class starts at eight."),
    "begin": ("begin class", "Let's begin."),
    "finish": ("finish homework", "I finish my homework."),
    "turn": ("turn left", "Turn left, please."),
    "wash": ("wash my hands", "Wash your hands."),
    "wear": ("wear a hat", "I wear a red hat."),
    "cook": ("cook dinner", "Mum can cook dinner."),
    "arrive": ("arrive at", "We arrive at school at eight."),
    "may": ("May I", "May I come in?"),
    "new year": ("Happy New Year", "Happy New Year!"),
    "children's day": ("Happy Children's Day", "Happy Children's Day!"),
    "national day": ("Happy National Day", "Happy National Day!"),
    "mid-autumn festival": ("Happy Mid-Autumn Festival", "Happy Mid-Autumn Festival!"),
    "spring festival": ("Happy Spring Festival", "Happy Spring Festival!"),
    "christmas": ("Merry Christmas", "Merry Christmas!"),
    "o'clock": ("seven o'clock", "It is seven o'clock."),
    "pe": ("PE class", "We have PE today."),
    "tv": ("watch TV", "I watch TV at night."),
    "t-shirt": ("a T-shirt", "I wear a T-shirt."),
}

# 固定短语/例句的中文（优先于规则推断）
SPECIAL_ZH: dict[str, tuple[str, str]] = {
    "i": ("我是", "我是一名学生。"),
    "you": ("谢谢你", "你好吗？"),
    "he": ("他是", "他是我的朋友。"),
    "she": ("她是", "她是我的姐姐。"),
    "it": ("它是", "它是一只猫。"),
    "we": ("我们是", "我们很开心。"),
    "they": ("他们是", "他们是我的同学。"),
    "me": ("帮助我", "你能帮助我吗？"),
    "him": ("和他一起", "我和他一起玩。"),
    "her": ("她的书", "这是她的书。"),
    "us": ("和我们一起", "跟我们一起来。"),
    "them": ("和他们一起", "我喜欢他们。"),
    "my": ("我的名字", "我的名字叫汤姆。"),
    "your": ("你的书", "这是你的书吗？"),
    "his": ("他的包", "这是他的包。"),
    "its": ("它的名字", "它叫什么名字？"),
    "our": ("我们的学校", "这是我们的学校。"),
    "their": ("他们的老师", "他们的老师很友善。"),
    "mine": ("这是我的", "这本书是我的。"),
    "yours": ("这是你的", "这是你的吗？"),
    "this": ("这是", "这是一支钢笔。"),
    "that": ("那是", "那是我的课桌。"),
    "these": ("这些是", "这些是我的书。"),
    "those": ("那些是", "那些是苹果。"),
    "who": ("谁是", "她是谁？"),
    "whose": ("谁的书", "这是谁的书？"),
    "what": ("什么是", "这是什么？"),
    "which": ("哪一个", "你喜欢哪一个？"),
    "where": ("在哪里", "我的包在哪里？"),
    "when": ("什么时候", "你的生日是什么时候？"),
    "why": ("为什么不", "你为什么开心？"),
    "how": ("你好吗", "你今天好吗？"),
    "a": ("一本书", "我有一本书。"),
    "an": ("一个苹果", "我吃一个苹果。"),
    "the": ("太阳", "太阳很明亮。"),
    "and": ("你和我", "汤姆和我是朋友。"),
    "or": ("茶或果汁", "你喜欢茶还是果汁？"),
    "but": ("但是我喜欢", "我累了，但是我很开心。"),
    "not": ("不要", "我不知道。"),
    "no": ("不，谢谢", "不，谢谢你。"),
    "yes": ("是的，我喜欢", "是的，我喜欢它。"),
    "ok": ("好的，我们走吧", "好的，我们走吧。"),
    "please": ("请坐下", "请坐下。"),
    "sorry": ("对不起", "对不起。"),
    "thank": ("谢谢你", "非常感谢你。"),
    "thanks": ("多谢", "非常感谢。"),
    "hello": ("大家好", "你好，我叫艾米。"),
    "hi": ("嗨，汤姆", "嗨，你好吗？"),
    "goodbye": ("说再见", "再见，怀特老师。"),
    "bye": ("再见", "再见，明天见。"),
    "welcome": ("欢迎来到", "欢迎来到我们学校。"),
    "pardon": ("请再说一遍", "对不起？请再说一遍。"),
    "in": ("在盒子里", "书在包里。"),
    "on": ("在桌子上", "钢笔在桌子上。"),
    "at": ("在学校", "我在学校。"),
    "to": ("去学校", "我去学校。"),
    "from": ("来自中国", "我来自中国。"),
    "for": ("给你", "这份礼物是给你的。"),
    "of": ("一杯", "我想要一杯茶。"),
    "with": ("和我一起", "跟我来。"),
    "about": ("大约十", "这本书是关于动物的。"),
    "under": ("在桌子下面", "猫在桌子下面。"),
    "behind": ("在门后面", "包在门后面。"),
    "beside": ("在我旁边", "坐在我旁边。"),
    "between": ("在 A 和 B 之间", "公园在学校和商店之间。"),
    "near": ("在学校附近", "我家在学校附近。"),
    "over": ("在那边", "看那边。"),
    "up": ("起立", "请起立。"),
    "down": ("坐下", "请坐下。"),
    "off": ("脱掉", "脱掉你的外套。"),
    "out": ("出去", "我们出去吧。"),
    "into": ("进房间", "请进房间。"),
    "after": ("放学后", "我放学后玩耍。"),
    "before": ("课前", "午饭前洗手。"),
    "again": ("再试一次", "请再说一遍。"),
    "also": ("我也喜欢", "我也喜欢苹果。"),
    "always": ("总是开心", "她总是很友善。"),
    "sometimes": ("有时玩耍", "我有时晚上读书。"),
    "never": ("从不迟到", "我从不迟到。"),
    "often": ("经常玩", "我们经常踢足球。"),
    "too": ("我也是", "我也喜欢它。"),
    "very": ("非常好", "它非常漂亮。"),
    "now": ("现在", "我们现在走吧。"),
    "then": ("然后", "先读，然后写。"),
    "here": ("过来", "请过来。"),
    "there": ("在那边", "看，那里有一只鸟。"),
    "today": ("今天是", "今天是星期一。"),
    "tomorrow": ("明天见", "明天见。"),
    "yesterday": ("昨天早上", "我昨天玩了。"),
    "all": ("我们全部", "我们都在这里。"),
    "some": ("一些水", "我想要一些牛奶。"),
    "any": ("任何问题", "你有什么问题吗？"),
    "many": ("许多书", "我有许多书。"),
    "much": ("太多", "这个多少钱？"),
    "lot": ("许多", "我有很多朋友。"),
    "little": ("一点", "我有一点累。"),
    "family": ("我的家人", "我爱我的家人。"),
    "father": ("我的爸爸", "这是我的爸爸。"),
    "dad": ("我的爸爸", "我爸爸是医生。"),
    "mother": ("我的妈妈", "这是我的妈妈。"),
    "mum": ("我的妈妈", "我妈妈是老师。"),
    "mom": ("我的妈妈", "我妈妈很友善。"),
    "parent": ("我的家长", "我的家长在家。"),
    "brother": ("我的哥哥/弟弟", "我有一个哥哥。"),
    "sister": ("我的姐姐/妹妹", "这是我的姐姐。"),
    "grandfather": ("我的爷爷/外公", "我的爷爷年纪大了。"),
    "grandpa": ("我的爷爷", "我爱我的爷爷。"),
    "grandmother": ("我的奶奶/外婆", "我的奶奶很会做饭。"),
    "grandma": ("我的奶奶", "我的奶奶很友善。"),
    "uncle": ("我的叔叔/舅舅", "这是我的叔叔。"),
    "aunt": ("我的阿姨/姑姑", "这是我的阿姨。"),
    "cousin": ("我的堂/表亲", "我的堂哥十岁。"),
    "baby": ("一个婴儿", "这个婴儿很可爱。"),
    "boy": ("一个男孩", "他是一个男孩。"),
    "girl": ("一个女孩", "她是一个女孩。"),
    "man": ("一个男人", "那个男人是老师。"),
    "woman": ("一个女人", "那个女人是我妈妈。"),
    "kid": ("一个小孩", "这个小孩很开心。"),
    "people": ("许多人", "公园里有许多人。"),
    "friend": ("我的朋友", "他是我的朋友。"),
    "mr": ("格林先生", "格林先生是我们的老师。"),
    "mrs": ("怀特夫人", "怀特夫人很友善。"),
    "miss": ("李老师", "李老师教英语。"),
    "ms": ("王女士", "王女士是我的阿姨。"),
    "am": ("我是", "我是一名小学生。"),
    "is": ("他是", "她是我的姐姐。"),
    "are": ("我们是", "我们是学生。"),
    "be": ("要快乐", "做一个好孩子。"),
    "can": ("我能", "我会游泳。"),
    "will": ("我会", "我会帮助你。"),
    "should": ("你应该", "你应该去睡觉。"),
    "must": ("你必须", "你必须洗手。"),
    "let": ("我们走吧", "我们一起玩吧。"),
    "have": ("看一看", "我有一本新书。"),
    "has": ("他有", "她有一只猫。"),
    "do": ("做作业", "我做我的作业。"),
    "go": ("回家", "我们回家吧。"),
    "come": ("进来", "请进。"),
    "come on": ("加油", "加油，你能行！"),
    "look": ("看", "看这幅画。"),
    "listen": ("听", "听老师说。"),
    "play": ("踢足球", "我放学后踢足球。"),
    "like": ("喜欢苹果", "我喜欢苹果。"),
    "love": ("爱你", "我爱我的妈妈。"),
    "want": ("想要", "我想吃东西。"),
    "need": ("需要帮助", "我需要你的帮助。"),
    "know": ("我知道", "我知道答案。"),
    "think": ("我认为", "我认为它很好。"),
    "see": ("我看见", "我看见一只鸟。"),
    "watch": ("看电视", "我晚上看电视。"),
    "hear": ("听见我", "你能听见我吗？"),
    "find": ("找到它", "我找不到我的钢笔。"),
    "get": ("起床", "我七点起床。"),
    "give": ("给我", "请给我一本书。"),
    "take": ("坐公交", "我坐公交去学校。"),
    "put": ("穿上", "穿上你的外套。"),
    "make": ("做蛋糕", "妈妈会做蛋糕。"),
    "eat": ("吃苹果", "我每天吃一个苹果。"),
    "drink": ("喝水", "请喝些水。"),
    "read": ("读书", "我读一本书。"),
    "write": ("写一个词", "请写下你的名字。"),
    "speak": ("说英语", "我会说英语。"),
    "talk": ("交谈", "我们和李老师谈谈吧。"),
    "tell": ("告诉我", "请给我讲个故事。"),
    "say": ("打招呼", "和你的朋友们打个招呼。"),
    "ask": ("问问题", "我可以问个问题吗？"),
    "answer": ("回答问题", "请回答这个问题。"),
    "study": ("学英语", "我每天学英语。"),
    "learn": ("学英语", "我在学校学英语。"),
    "teach": ("教我", "李老师教我们英语。"),
    "help": ("帮助我", "你能帮助我吗？"),
    "work": ("去上班", "我爸爸去上班。"),
    "swim": ("去游泳", "我会游泳。"),
    "run": ("跑得快", "我能跑得很快。"),
    "jump": ("跳得高", "青蛙会跳。"),
    "walk": ("步行去学校", "我步行去学校。"),
    "dance": ("跳舞跳得好", "她舞跳得很好。"),
    "sing": ("唱一首歌", "我们唱一首歌吧。"),
    "draw": ("画一幅画", "我会画猫。"),
    "open": ("开门", "请开门。"),
    "close": ("关窗", "请关窗。"),
    "sit": ("坐下", "请坐下。"),
    "stand": ("起立", "请起立。"),
    "sleep": ("去睡觉", "我九点睡觉。"),
    "wake": ("醒来", "我七点醒来。"),
    "buy": ("买一本书", "我想买一本书。"),
    "boil": ("一个疖子", "他胳膊上长了一个疖子。"),
    "sell": ("卖水果", "他们卖苹果。"),
    "use": ("用钢笔", "我用铅笔。"),
    "try": ("再试一次", "请再试一次。"),
    "keep": ("保持安静", "请保持安静。"),
    "live": ("住在", "我住在北京。"),
    "move": ("继续", "请搬一下你的椅子。"),
    "meet": ("很高兴见到你", "很高兴见到你。"),
    "call": ("给我打电话", "请明天给我打电话。"),
    "show": ("给我看", "给我看看你的书。"),
    "share": ("分享", "我们一起分蛋糕吧。"),
    "catch": ("接球", "我会接球。"),
    "fly": ("放风筝", "我在公园放风筝。"),
    "ride": ("骑自行车", "我骑自行车去学校。"),
    "drive": ("开车", "我爸爸会开车。"),
    "hurt": ("伤到腿", "我的腿疼。"),
    "feel": ("感到开心", "我今天感到开心。"),
    "hope": ("我希望", "我希望你一切都好。"),
    "worry": ("别担心", "别担心。"),
    "hurry": ("快点", "请快点。"),
    "exercise": ("做运动", "我每天早做运动。"),
    "visit": ("看望奶奶", "我星期天看望奶奶。"),
    "travel": ("坐火车旅行", "我们坐火车旅行。"),
    "wait": ("等待", "请等我。"),
    "stop": ("停止说话", "请停下来。"),
    "start": ("开始上课", "八点开始上课。"),
    "begin": ("开始上课", "我们开始吧。"),
    "finish": ("完成作业", "我完成作业了。"),
    "turn": ("向左转", "请向左转。"),
    "wash": ("洗手", "洗你的手。"),
    "wear": ("戴帽子", "我戴一顶红帽子。"),
    "cook": ("做晚饭", "妈妈会做晚饭。"),
    "arrive": ("到达", "我们八点到学校。"),
    "may": ("我可以吗", "我可以进来吗？"),
    "new year": ("新年快乐", "新年快乐！"),
    "children's day": ("儿童节快乐", "儿童节快乐！"),
    "national day": ("国庆节快乐", "国庆节快乐！"),
    "mid-autumn festival": ("中秋节快乐", "中秋节快乐！"),
    "spring festival": ("春节快乐", "春节快乐！"),
    "christmas": ("圣诞快乐", "圣诞快乐！"),
    "o'clock": ("七点钟", "现在是七点钟。"),
    "pe": ("体育课", "我们今天有体育课。"),
    "tv": ("看电视", "我晚上看电视。"),
    "t-shirt": ("一件 T 恤", "我穿着一件 T 恤。"),
}


def _article(term: str) -> str:
    key = term.lower()
    if key in UNCOUNTABLE or " " in term:
        return term
    if term.endswith("s") and not term.endswith("ss"):
        return term
    first = term.lstrip()[:1].lower()
    if first in VOWELS:
        return f"an {term}"
    return f"a {term}"


def _clip(text: str, limit: int) -> str:
    text = " ".join(text.split())
    return text[:limit]


def first_sense(meaning: str) -> str:
    m = (meaning or "").strip()
    m = re.sub(r"[（(][^）)]*[）)]", "", m).strip()
    for sep in ("；", ";", "，", ",", "、", "/"):
        if sep in m:
            m = m.split(sep, 1)[0].strip()
    return m


def _adj_stem(gloss: str) -> str:
    """去掉释义末尾的「的」，便于再拼「的书 / 的一天」。"""
    g = (gloss or "").strip()
    return g[:-1] if g.endswith("的") else g


def _measure(term: str, gloss: str) -> str:
    """小学常用量词。"""
    t = (term or "").lower()
    g = gloss or ""
    if t in {"book", "notebook", "dictionary", "magazine", "newspaper"} or "书" in g:
        return "一本"
    if any(x in g for x in ("猫", "狗", "鸟", "兔", "猪", "鸡", "鸭", "鱼", "熊", "虎")):
        return "一只"
    if any(x in g for x in ("水", "奶", "汁", "茶", "汤", "油")):
        return "一杯"
    return "一个"


def translate_usage(
    term: str,
    meaning: str,
    notes: str,
    phrase: str,
    example: str,
) -> tuple[str, str]:
    """根据英文短语/例句与单词释义，生成对应中文。"""
    key = (term or "").strip().lower()
    if key in SPECIAL_ZH:
        return SPECIAL_ZH[key]

    g = first_sense(meaning) or term
    p = (phrase or "").strip()
    e = (example or "").strip()
    pl = p.lower()
    el = e.lower()
    raw = (term or "").strip()
    raw_l = raw.lower()
    m = _measure(raw_l, g)
    adj = _adj_stem(g)

    # —— 分类备注 ——
    if "数词" in notes and "序数" not in notes:
        if key == "zero":
            return "零个苹果", "有零个苹果。"
        if key == "one":
            return "一本书", "我有一本书。"
        if key == "hundred":
            return "一百", "我有一百张邮票。"
        return f"{g}本书", f"我有{g}本书。"

    if "序数词" in notes:
        return f"第{g}天", f"今天是第{g}天。"

    if "星期" in notes:
        return f"在{g}", f"我们{g}有英语课。"

    if "月份" in notes:
        return f"在{g}", f"我的生日在{g}。"

    if "节日" in notes:
        return f"{g}快乐", f"{g}快乐！"

    # —— 高频模板 ——
    phrase_zh = ""
    example_zh = ""

    if pl in {f"a {raw_l}", f"an {raw_l}"}:
        phrase_zh = f"{m}{g}"
        if el in {f"this is a {raw_l}.", f"this is an {raw_l}."}:
            example_zh = f"这是{m}{g}。"
        elif el == f"i like {raw_l}.":
            example_zh = f"我喜欢{g}。"

    if pl == raw_l:
        phrase_zh = phrase_zh or g
        if el == f"i like {raw_l}.":
            example_zh = f"我喜欢{g}。"

    if pl == f"a {raw_l} book":
        phrase_zh = f"一本{adj}的书"
        if el == f"this is a {raw_l} book.":
            example_zh = f"这是一本{adj}的书。"

    if pl == f"{raw_l} day":
        phrase_zh = f"{adj}的一天"
        if el == f"it is a {raw_l} day.":
            example_zh = f"这是{adj}的一天。"

    if pl == f"{raw_l} books":
        phrase_zh = f"{g}本书"
        if el == f"i have {raw_l} books.":
            example_zh = f"我有{g}本书。"

    if pl == f"the {raw_l} day":
        phrase_zh = f"第{g}天"
        if el == f"today is the {raw_l} day.":
            example_zh = f"今天是第{g}天。"

    if pl == f"in {raw_l}":
        phrase_zh = f"在{g}"
        if el == f"my birthday is in {raw_l}.":
            example_zh = f"我的生日在{g}。"

    if pl == f"on {raw_l}":
        phrase_zh = f"在{g}"
        if el == f"we have english on {raw_l}.":
            example_zh = f"我们{g}有英语课。"

    if pl == f"my {raw_l}":
        phrase_zh = f"我的{g}"
        if el == f"this is my {raw_l}.":
            example_zh = f"这是我的{g}。"

    if pl == f"happy {raw_l}":
        phrase_zh = f"{g}快乐"
        if el in {f"happy {raw_l}!", f"happy {raw_l}."}:
            example_zh = f"{g}快乐！"

    if pl == f"{raw_l} it":
        phrase_zh = f"{g}它"
        if el == f"i {raw_l} it every day.":
            example_zh = f"我每天都{g}它。"

    if pl == f"{raw_l} now":
        phrase_zh = f"现在{g}"
        if el == f"let's {raw_l}.":
            example_zh = f"我们{g}吧。"

    if not phrase_zh:
        # 把英文里的单词替换成释义，再套一层简短说明
        if raw_l and raw_l in pl:
            phrase_zh = p
            for form in {raw, raw_l, raw.capitalize()}:
                phrase_zh = phrase_zh.replace(form, g)
            # 仍全是拉丁字母则退回释义
            if re.fullmatch(r"[A-Za-z0-9 \-'.]+", phrase_zh):
                phrase_zh = g
        else:
            phrase_zh = g

    if not example_zh:
        if el == f"this is a {raw_l}.":
            example_zh = f"这是一个{g}。"
        elif el == f"this is an {raw_l}.":
            example_zh = f"这是一个{g}。"
        elif el == f"i like {raw_l}.":
            example_zh = f"我喜欢{g}。"
        elif el.startswith("let's ") and el.endswith("."):
            example_zh = f"我们{g}吧。"
        elif raw_l and raw_l in el:
            example_zh = e
            for form in {raw, raw_l, raw.capitalize()}:
                example_zh = example_zh.replace(form, g)
            if re.fullmatch(r"[A-Za-z0-9 \-'.?!]+", example_zh):
                example_zh = f"例句：{g}。"
            elif not example_zh.endswith(("。", "！", "？")):
                example_zh += "。"
        else:
            example_zh = f"和「{g}」有关的例句。"

    return _clip(phrase_zh, 200), _clip(example_zh, 400)


def usage_for(term: str, meaning: str = "", notes: str = "") -> tuple[str, str, str, str]:
    """返回 (短语, 短语中文, 例句, 例句中文)。"""
    raw = (term or "").strip()
    if not raw:
        return "", "", "", ""
    key = raw.lower()

    if "数词" in notes and "序数" not in notes:
        if key == "zero":
            phrase, example = "zero apples", "There are zero apples."
        elif key == "one":
            phrase, example = "one book", "I have one book."
        elif key == "hundred":
            phrase, example = "one hundred", "I have one hundred stamps."
        else:
            phrase, example = f"{raw} books", f"I have {raw} books."
    elif "序数词" in notes:
        phrase, example = f"the {raw} day", f"Today is the {raw} day."
    elif "星期" in notes:
        phrase, example = f"on {raw}", f"We have English on {raw}."
    elif "月份" in notes:
        phrase, example = f"in {raw}", f"My birthday is in {raw}."
    elif key in SPECIAL:
        phrase, example = SPECIAL[key]
        phrase, example = _clip(phrase, 200), _clip(example, 400)
    elif "节日" in notes:
        phrase, example = f"Happy {raw}", f"Happy {raw}!"
    elif key in VERBS:
        phrase, example = f"{raw} it", f"I {raw} it every day."
    else:
        meaning = meaning or ""
        if meaning.endswith("的") or "的；" in meaning or meaning.endswith("的）"):
            if key.endswith("y") and key not in {"boy", "toy", "day"}:
                phrase, example = f"{raw} day", f"It is a {raw} day."
            else:
                phrase, example = f"a {raw} book", f"This is a {raw} book."
        elif any(mark in meaning for mark in ("……", "；下雨", "；下雪", "；购物")):
            phrase, example = f"{raw} now", f"Let's {raw}."
        else:
            phrase = _article(raw)
            if phrase.lower() == raw.lower():
                example = f"I like {raw}."
            else:
                example = f"This is {phrase}."
            phrase, example = _clip(phrase, 200), _clip(example, 400)

    phrase_zh, example_zh = translate_usage(raw, meaning, notes, phrase, example)
    return phrase, phrase_zh, example, example_zh
